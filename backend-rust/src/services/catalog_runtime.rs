//! Per-database catalog snapshots and request-scoped historical references.
use crate::{error::AppError,services::{catalog_editor,question_storage}};
use bson::{doc,Bson,Document};
use mongodb::Database;
use serde::{Deserialize,Serialize};
use serde_json::{json,Value};
use futures::TryStreamExt;
use std::{collections::{BTreeMap,HashSet},future::Future,sync::{Arc,OnceLock,RwLock}};

#[derive(Clone,PartialEq,Eq)]
pub struct References {pub subjects:HashSet<String>,pub topics:HashSet<(String,String)>,pub active:HashSet<(String,String)>}
impl References {
    fn of(c:&Value)->Self {
        let subjects=c["subjects"].as_array().unwrap().iter().map(|s|s["subject_id"].as_str().unwrap().to_owned()).collect();
        let active_subjects:HashSet<_>=c["subjects"].as_array().unwrap().iter().filter(|s|s["status"]=="ACTIVE").map(|s|s["subject_id"].as_str().unwrap()).collect();
        let topics=c["topics"].as_array().unwrap().iter().map(|t|(t["subject_id"].as_str().unwrap().into(),t["topic_id"].as_str().unwrap().into())).collect();
        let active=c["topics"].as_array().unwrap().iter().filter(|t|t["status"]=="ACTIVE" && active_subjects.contains(t["subject_id"].as_str().unwrap())).map(|t|(t["subject_id"].as_str().unwrap().into(),t["topic_id"].as_str().unwrap().into())).collect();
        Self{subjects,topics,active}
    }
}
#[derive(Clone)]
pub struct Snapshot {
    pub current:Arc<Value>,pub registry:Arc<Value>,pub hash:String,pub version:i64,pub activation_id:Option<String>,
    pub references:BTreeMap<i64,Arc<References>>,pub committed:HashSet<String>,
}
pub fn bundled()->Arc<Snapshot> {
    static BASE:OnceLock<Arc<Snapshot>>=OnceLock::new();
    BASE.get_or_init(||{
        let catalog:Value=serde_json::from_str(include_str!("../../../shared/knowledge-catalog.json")).unwrap();
        let version=catalog["catalog_version"].as_i64().unwrap();let refs=Arc::new(References::of(&catalog));
        Arc::new(Snapshot{hash:catalog_editor::hash(&catalog),version,current:Arc::new(catalog),registry:Arc::new(serde_json::from_str(include_str!("../../../shared/knowledge-id-registry.json")).unwrap()),activation_id:None,references:BTreeMap::from([(version,refs)]),committed:HashSet::new()})
    }).clone()
}
tokio::task_local! {static CONTEXT:Arc<Snapshot>;}
pub fn snapshot()->Arc<Snapshot>{CONTEXT.try_with(Arc::clone).unwrap_or_else(|_|bundled())}
pub async fn scope<T>(catalog:Arc<Snapshot>,future:impl Future<Output=T>)->T{CONTEXT.scope(catalog,future).await}
pub fn version_known(version:i64)->bool{snapshot().references.contains_key(&version)}
pub fn topic_at(version:i64,subject:&str,topic:&str)->bool{snapshot().references.get(&version).is_some_and(|r|r.active.contains(&(subject.into(),topic.into())))}
pub fn registered(subject:&str,topic:Option<&str>)->bool{let s=snapshot();let refs=&s.references[&s.version];topic.map_or_else(||refs.subjects.contains(subject),|t|refs.topics.contains(&(subject.into(),t.into())))}

pub struct Runtime {cache:RwLock<Arc<Snapshot>>,refresh_lock:tokio::sync::Mutex<()>}
impl Default for Runtime {fn default()->Self{Self{cache:RwLock::new(bundled()),refresh_lock:tokio::sync::Mutex::new(())}}}
fn conflict(s:&str)->AppError{AppError::Conflict(s.into())}
fn json_doc(d:Document)->Value{Bson::Document(d).into_relaxed_extjson()}
impl Runtime {
    pub async fn refresh(&self,db:&Database)->Result<Arc<Snapshot>,AppError>{
        let _guard=self.refresh_lock.lock().await;
        let head=question_storage::collection(db,"knowledge_catalog_head").find_one(doc!{"_id":"cogniva-knowledge"}).await?;
        let cached=self.cache.read().unwrap().clone();
        let Some(head)=head else {
            if cached.activation_id.is_some(){return Err(conflict("Ponteiro do catálogo desapareceu; histórico preservado para revisão"));}
            return Ok(cached);
        };
        let activation=head.get_str("activation_id").map_err(|_|conflict("Ponteiro do catálogo inválido"))?;
        if cached.activation_id.as_deref()==Some(activation) && head.get_str("catalog_sha256").ok()==Some(cached.hash.as_str()) && head.get_i64("version").ok()==Some(cached.version){return Ok(cached);}
        let mut lineage=Vec::new();let mut cursor=Some(activation.to_owned());let mut visited=HashSet::new();
        let events=question_storage::collection(db,"knowledge_catalog_activations");
        while let Some(id)=cursor {
            if !visited.insert(id.clone()){return Err(conflict("Ciclo no histórico do catálogo"));}
            let event=events.find_one(doc!{"_id":&id}).projection(doc!{"catalog":0,"registry":0}).await?.ok_or_else(||conflict("Ativação sem snapshot recuperável"))?;
            cursor=event.get_str("parent_activation_id").ok().map(str::to_owned);lineage.push(id);
        }
        let mut next=(*bundled()).clone();
        let base=question_storage::collection(db,"knowledge_catalog_versions").find_one(doc!{"_id":format!("cogniva-knowledge:{}",next.version)}).await?.ok_or_else(||conflict("Snapshot do catálogo base ausente"))?;
        if json_doc(base)["payload"]!=*next.current{return Err(conflict("Snapshot do catálogo base divergente"));}
        for id in lineage.into_iter().rev(){
            let event=json_doc(events.find_one(doc!{"_id":&id}).await?.ok_or_else(||conflict("Snapshot do catálogo ausente"))?);
            if event["base_catalog_sha256"]!=next.hash || event["catalog"]["catalog_version"].as_i64()!=Some(next.version+1) || event["catalog_sha256"]!=catalog_editor::hash(&event["catalog"]) {return Err(conflict("Cadeia de versões do catálogo divergente"));}
            let validated=catalog_editor::validate_against(&event["catalog"],&next.current,&next.registry)?;
            if validated["registry"]!=event["registry"]{return Err(conflict("Registro de identidades divergente na ativação"));}
            let refs=References::of(&event["catalog"]);let previous=&next.references[&next.version];
            let refs=if **previous==refs{previous.clone()}else{Arc::new(refs)};
            next.version+=1;next.references.insert(next.version,refs);next.current=Arc::new(event["catalog"].clone());next.registry=Arc::new(event["registry"].clone());next.hash=catalog_editor::hash(&next.current);next.activation_id=Some(id.clone());next.committed.insert(id);
        }
        if head.get_i64("version").ok()!=Some(next.version) || head.get_str("catalog_sha256").ok()!=Some(next.hash.as_str()){return Err(conflict("Ponteiro e snapshot do catálogo divergem"));}
        archive(db,&next.current).await?;
        let next=Arc::new(next);*self.cache.write().unwrap()=next.clone();Ok(next)
    }
}
pub async fn activation_status(state:&crate::AppState,user:i64,operation:&str)->Result<Value,AppError>{
    uuid::Uuid::parse_str(operation).map_err(|_|conflict("Operação inválida"))?;
    let current=state.catalog.refresh(&state.db).await?;let key=format!("{user}:{operation}");
    let record=question_storage::collection(&state.db,"knowledge_catalog_activations").find_one(doc!{"_id":&key}).projection(doc!{"catalog":0,"registry":0}).await?;
    Ok(json!({"operation_id":operation,"status":if current.committed.contains(&key){"applied"}else if record.is_some(){"reserved"}else{"absent"},"current_version":current.version,"current_hash":current.hash,"record":record.map(json_doc)}))
}
pub async fn history(state:&crate::AppState,before:Option<i64>)->Result<Value,AppError>{
    let current=state.catalog.refresh(&state.db).await?;
    let rows:Vec<Document>=question_storage::collection(&state.db,"knowledge_catalog_activations").find(doc!{"_id":{"$in":current.committed.iter().cloned().collect::<Vec<_>>()} ,"version":{"$lt":before.unwrap_or(i64::MAX)}}).sort(doc!{"version":-1}).limit(50).projection(doc!{"catalog":0,"registry":0,"request_sha256":0}).await?.try_collect().await?;
    Ok(json!({"current_version":current.version,"versions":rows.into_iter().map(json_doc).collect::<Vec<_>>()}))
}
pub async fn version(state:&crate::AppState,version:i64)->Result<Value,AppError>{
    let current=state.catalog.refresh(&state.db).await?;
    if !current.references.contains_key(&version){return Err(AppError::NotFound("Versão não ativada".into()));}
    if version==bundled().version{return Ok(json!({"catalog":bundled().current.as_ref(),"registry":bundled().registry.as_ref()}));}
    let row=question_storage::collection(&state.db,"knowledge_catalog_activations").find_one(doc!{"_id":{"$in":current.committed.iter().cloned().collect::<Vec<_>>()} ,"version":version}).await?.ok_or_else(||conflict("Snapshot histórico ausente"))?;
    let row=json_doc(row);
    if row["catalog_sha256"]!=catalog_editor::hash(&row["catalog"]){return Err(conflict("Snapshot histórico divergente"));}
    Ok(json!({"catalog":row["catalog"],"registry":row["registry"]}))
}
async fn archive(db:&Database,catalog:&Value)->Result<(),AppError>{
    let version=catalog["catalog_version"].as_i64().unwrap();let key=format!("cogniva-knowledge:{version}");let coll=question_storage::collection(db,"knowledge_catalog_versions");
    if let Some(old)=coll.find_one(doc!{"_id":&key}).await?{
        if json_doc(old)["payload"]!=*catalog{return Err(conflict("Versão imutável do catálogo divergente"));}
    }else{
        let payload=Bson::try_from(catalog.clone()).map_err(|_|conflict("Catálogo não armazenável"))?;
        coll.update_one(doc!{"_id":&key},doc!{"$setOnInsert":{"catalog_id":"cogniva-knowledge","version":version,"payload":payload}}).upsert(true).await?;
        let stored=json_doc(coll.find_one(doc!{"_id":key}).await?.ok_or_else(||conflict("Snapshot não confirmado"))?);
        if stored["payload"]!=*catalog{return Err(conflict("Snapshot concorrente divergente"));}
    }Ok(())
}

#[derive(Clone,Deserialize,Serialize)]
#[serde(deny_unknown_fields)]
pub struct ActivateRequest {pub operation_id:String,pub draft_id:String,pub expected_draft_revision:i64,pub expected_catalog_hash:String,pub reason:String,pub reviewed:bool}
pub async fn activate(state:&crate::AppState,user:i64,r:ActivateRequest)->Result<Value,AppError>{
    uuid::Uuid::parse_str(&r.operation_id).map_err(|_|conflict("Operação inválida"))?;
    uuid::Uuid::parse_str(&r.draft_id).map_err(|_|conflict("Proposta inválida"))?;
    if !r.reviewed || !(10..=2000).contains(&r.reason.trim().len()){return Err(AppError::BadRequest("Confirme a revisão e informe o motivo da ativação (10 a 2000 bytes)".into()));}
    let operation=format!("{user}:{}",r.operation_id);let request_hash=catalog_editor::hash(&json!({"actor":user,"request":r}));
    let current=state.catalog.refresh(&state.db).await?;
    let events=question_storage::collection(&state.db,"knowledge_catalog_activations");
    let event=if let Some(old)=events.find_one(doc!{"_id":&operation}).await?{
        if old.get_str("request_sha256").ok()!=Some(&request_hash){return Err(conflict("Operação já reservada para outra ativação"));}old
    }else{
        if current.hash!=r.expected_catalog_hash{return Err(conflict("O catálogo ativo mudou; compare a proposta antes de ativar"));}
        let draft=catalog_editor::get(state,user,&r.draft_id).await?;
        if draft["revision"].as_i64()!=Some(r.expected_draft_revision) || draft["status"]!="REVIEW_READY" || draft["base_catalog_sha256"]!=current.hash{return Err(conflict("Proposta não revisada ou alterada; confira a revisão salva"));}
        let validation=catalog_editor::validate_against(&draft["catalog"],&current.current,&current.registry)?;
        archive(&state.db,&current.current).await?;
        let event=doc!{"_id":&operation,"operation_id":&r.operation_id,"actor_user_id":user,"draft_id":&r.draft_id,"draft_revision":r.expected_draft_revision,"request_sha256":request_hash,"reason":&r.reason,"recorded_at":bson::DateTime::now(),"parent_activation_id":current.activation_id.clone(),"base_catalog_sha256":&current.hash,"catalog_sha256":validation["catalog_sha256"].as_str().unwrap(),"version":current.version+1,"catalog":Bson::try_from(draft["catalog"].clone()).map_err(|_|conflict("Catálogo BSON inválido"))?,"registry":Bson::try_from(validation["registry"].clone()).map_err(|_|conflict("Registro BSON inválido"))?};
        events.insert_one(event.clone()).await?;event
    };
    let version=event.get_i64("version").map_err(|_|conflict("Versão de ativação inválida"))?;
    if current.committed.contains(&operation){return Ok(json!({"status":"already_applied","operation_id":r.operation_id,"activated_version":version,"current_version":current.version,"catalog_sha256":event.get_str("catalog_sha256").unwrap_or("")}));}
    if event.get_str("base_catalog_sha256").ok()!=Some(&current.hash){return Err(conflict("Outra versão foi ativada; o comando original continua preservado"));}
    let candidate=json_doc(event.clone())["catalog"].clone();
    // A conflicting immutable snapshot is a corruption signal, never overwritten.
    if let Some(old)=question_storage::collection(&state.db,"knowledge_catalog_versions").find_one(doc!{"_id":format!("cogniva-knowledge:{version}")}).await?{if json_doc(old)["payload"]!=candidate{return Err(conflict("Snapshot reservado da versão diverge da proposta"));}}
    let head=doc!{"_id":"cogniva-knowledge","activation_id":&operation,"version":version,"catalog_sha256":event.get_str("catalog_sha256").unwrap(),"updated_at":bson::DateTime::now()};
    let heads=question_storage::collection(&state.db,"knowledge_catalog_head");
    if let Some(parent)=&current.activation_id{
        let result=heads.replace_one(doc!{"_id":"cogniva-knowledge","activation_id":parent,"catalog_sha256":&current.hash},head).await?;
        if result.matched_count!=1{return Err(conflict("Ativação concorrente; confira o catálogo atual e preserve seu comando"));}
    }else{heads.insert_one(head).await?;}
    let applied=state.catalog.refresh(&state.db).await?;
    if !applied.committed.contains(&operation){return Err(conflict("Ativação ainda não confirmada"));}
    Ok(json!({"status":"applied","operation_id":r.operation_id,"activated_version":version,"current_version":applied.version,"catalog_sha256":event.get_str("catalog_sha256").unwrap()}))
}
