use crate::{
    error::AppError,
    models::contest::{
        ConfidenceBadge, ContestDates, ContestDocument, ContestSourceRef, ContestStatus,
        EditalDiffSummary, EstimationMetadata, ScoringRules, SyllabusTopicDocument,
        TopicDiffStatus,
    },
};
use bson::doc;
use mongodb::Database;
use std::collections::HashMap;

/// Popula as coleções `concursos` e `editais_topicos` com os concursos reais do AFTBot.py
/// (CPNU, RFB, BNDES) + exemplos de estados (Edital Publicado, Autorizado, Previsto)
/// caso a coleção esteja vazia.
pub async fn ensure_seed_data(db: &Database) -> Result<usize, AppError> {
    let contests_coll = db.collection::<ContestDocument>("concursos");
    let existing = contests_coll.count_documents(doc! {}).await?;
    if existing > 0 {
        return Ok(0);
    }

    let seed_contests = vec![
        ContestDocument {
            id: "CPNU".to_string(),
            code: "CPNU".to_string(),
            name: "CPNU · Auditor Fiscal do Trabalho (AFT)".to_string(),
            role_title: "Auditor-Fiscal do Trabalho".to_string(),
            career_area: "Fiscalização / Trabalho".to_string(),
            uf: "Nacional".to_string(),
            status: ContestStatus::EditalPublicado,
            exam_board: "Cesgranrio".to_string(),
            is_board_confirmed: true,
            dates: ContestDates {
                exam_date_official: Some("2026-12-13T13:00:00Z".to_string()),
                estimated_window: None,
                edital_published_at: Some("2026-08-15T00:00:00Z".to_string()),
            },
            scoring_rules: ScoringRules {
                question_format: "MULTIPLE_CHOICE_5".to_string(),
                wrong_answer_penalty: 0.0,
                historical_cutoff_pct: 76.0,
            },
            simulation_proportions: HashMap::from([
                ("Administração Pública Federal".to_string(), 0.04),
                ("Desafios do Estado de Direito".to_string(), 0.05),
                ("Ética e Integridade".to_string(), 0.05),
                ("Diversidade e Inclusão na Sociedade".to_string(), 0.04),
                ("Finanças Públicas".to_string(), 0.04),
                ("Políticas Públicas".to_string(), 0.105),
                ("Gestão Governamental e Governança Pública".to_string(), 0.075),
                ("Sociologia e Psicologia Aplicadas ao Trabalho".to_string(), 0.15),
                ("Segurança e Saúde do Trabalhador".to_string(), 0.225),
                ("Direito do Trabalho".to_string(), 0.225),
            ]),
            blocks: HashMap::from([
                (
                    "Conhecimentos Gerais".to_string(),
                    vec![
                        "Políticas Públicas".to_string(),
                        "Desafios do Estado de Direito".to_string(),
                        "Ética e Integridade".to_string(),
                        "Diversidade e Inclusão na Sociedade".to_string(),
                        "Administração Pública Federal".to_string(),
                        "Finanças Públicas".to_string(),
                    ],
                ),
                (
                    "Conhecimentos Específicos".to_string(),
                    vec![
                        "Gestão Governamental e Governança Pública".to_string(),
                        "Políticas Públicas".to_string(),
                        "Sociologia e Psicologia Aplicadas ao Trabalho".to_string(),
                        "Segurança e Saúde do Trabalhador".to_string(),
                        "Direito do Trabalho".to_string(),
                    ],
                ),
            ]),
            sources: vec![ContestSourceRef {
                title: "Edital CPNU Bloco 4 — Trabalho e Saúde do Servidor (DOU)".to_string(),
                url: Some("https://www.gov.br/gestao/pt-br/concursonacional".to_string()),
                source_type: "OFFICIAL_EDITAL".to_string(),
                updated_at: "2026-08-15".to_string(),
            }],
            latest_diff_summary: Some(EditalDiffSummary {
                previous_label: "Edital Estimado Pré-CPNU".to_string(),
                published_label: "Edital Oficial CPNU Bloco 4".to_string(),
                confirmed_topics_count: 42,
                new_topics_count: 5,
                removed_topics_count: 2,
                confirmed_board: "Cesgranrio".to_string(),
            }),
        },
        ContestDocument {
            id: "RFB".to_string(),
            code: "RFB".to_string(),
            name: "Receita Federal · Auditor Fiscal".to_string(),
            role_title: "Auditor-Fiscal da Receita Federal".to_string(),
            career_area: "Fiscal".to_string(),
            uf: "Nacional".to_string(),
            status: ContestStatus::SolicitadoAutorizado,
            exam_board: "FGV / Cebraspe".to_string(),
            is_board_confirmed: false,
            dates: ContestDates {
                exam_date_official: None,
                estimated_window: Some("1º sem/2027".to_string()),
                edital_published_at: None,
            },
            scoring_rules: ScoringRules {
                question_format: "MULTIPLE_CHOICE_5".to_string(),
                wrong_answer_penalty: 0.0,
                historical_cutoff_pct: 72.5,
            },
            simulation_proportions: HashMap::from([
                ("Contabilidade Geral".to_string(), 0.07),
                ("Contabilidade Pública".to_string(), 0.07),
                ("Contabilidade Tributária".to_string(), 0.07),
                ("Direito Administrativo".to_string(), 0.07),
                ("Direito Civil".to_string(), 0.07),
                ("Direito Constitucional".to_string(), 0.07),
                ("Direito Internacional".to_string(), 0.05),
                ("Direito Tributário".to_string(), 0.14),
                ("Economia".to_string(), 0.07),
                ("Estatística".to_string(), 0.07),
                ("Finanças Públicas".to_string(), 0.07),
                ("Informática".to_string(), 0.05),
                ("Legislação Tributária".to_string(), 0.07),
                ("Matemática Financeira".to_string(), 0.06),
            ]),
            blocks: HashMap::from([
                (
                    "Conhecimentos Gerais".to_string(),
                    vec![
                        "Direito Administrativo".to_string(),
                        "Direito Civil".to_string(),
                        "Direito Constitucional".to_string(),
                        "Direito Internacional".to_string(),
                        "Direito Tributário".to_string(),
                    ],
                ),
                (
                    "Conhecimentos Específicos".to_string(),
                    vec![
                        "Contabilidade Geral".to_string(),
                        "Contabilidade Pública".to_string(),
                        "Contabilidade Tributária".to_string(),
                        "Direito Tributário".to_string(),
                        "Economia".to_string(),
                        "Estatística".to_string(),
                        "Finanças Públicas".to_string(),
                        "Informática".to_string(),
                        "Legislação Tributária".to_string(),
                        "Matemática Financeira".to_string(),
                    ],
                ),
            ]),
            sources: vec![ContestSourceRef {
                title: "Último Edital RFB 2023 (FGV) + Nota de Solicitação MGI".to_string(),
                url: None,
                source_type: "LAST_EDITAL".to_string(),
                updated_at: "2026-09-10".to_string(),
            }],
            latest_diff_summary: None,
        },
        ContestDocument {
            id: "BNDES".to_string(),
            code: "BNDES".to_string(),
            name: "BNDES · Analista".to_string(),
            role_title: "Profissional Básico — Administração / Economia".to_string(),
            career_area: "Bancária / Desenvolvimento".to_string(),
            uf: "RJ / Nacional".to_string(),
            status: ContestStatus::Previsto,
            exam_board: "Cesgranrio".to_string(),
            is_board_confirmed: false,
            dates: ContestDates {
                exam_date_official: None,
                estimated_window: Some("2º sem/2027".to_string()),
                edital_published_at: None,
            },
            scoring_rules: ScoringRules {
                question_format: "MULTIPLE_CHOICE_5".to_string(),
                wrong_answer_penalty: 0.0,
                historical_cutoff_pct: 71.0,
            },
            simulation_proportions: HashMap::from([
                ("Probabilidade e Estatística".to_string(), 0.046),
                ("Fundamentos Econômicos".to_string(), 0.046),
                ("Matemática Financeira".to_string(), 0.046),
                ("Contabilidade Geral, Gerencial e de Custos".to_string(), 0.088),
                ("Administração e Gestão Estratégica".to_string(), 0.08),
                ("Análise Setorial".to_string(), 0.046),
                ("Sustentabilidade e Mudanças Climáticas".to_string(), 0.088),
                ("Análise e Avaliação de Projetos".to_string(), 0.088),
                ("Finanças Corporativas".to_string(), 0.046),
                ("Mercado de Capitais".to_string(), 0.046),
                ("Inovação e Empreendedorismo".to_string(), 0.046),
                ("Gestão de Projetos".to_string(), 0.088),
                ("Papel do BNDES no desenvolvimento brasileiro".to_string(), 0.046),
                ("Clima, Sustentabilidade e Responsabilidade Socioambiental e Climática".to_string(), 0.046),
                ("Princípios de análise de dados e informações".to_string(), 0.046),
                ("Diversidade e Inclusão".to_string(), 0.016),
                ("Língua Portuguesa".to_string(), 0.046),
                ("Língua Inglesa".to_string(), 0.046),
            ]),
            blocks: HashMap::from([
                (
                    "Conhecimentos Básicos".to_string(),
                    vec![
                        "Língua Portuguesa".to_string(),
                        "Língua Inglesa".to_string(),
                    ],
                ),
                (
                    "Conhecimentos Transversais".to_string(),
                    vec![
                        "Papel do BNDES no desenvolvimento brasileiro".to_string(),
                        "Clima, Sustentabilidade e Responsabilidade Socioambiental e Climática".to_string(),
                        "Princípios de análise de dados e informações".to_string(),
                        "Diversidade e Inclusão".to_string(),
                    ],
                ),
                (
                    "Conhecimentos Específicos".to_string(),
                    vec![
                        "Probabilidade e Estatística".to_string(),
                        "Fundamentos Econômicos".to_string(),
                        "Matemática Financeira".to_string(),
                        "Contabilidade Geral, Gerencial e de Custos".to_string(),
                        "Administração e Gestão Estratégica".to_string(),
                        "Análise Setorial".to_string(),
                        "Sustentabilidade e Mudanças Climáticas".to_string(),
                        "Análise e Avaliação de Projetos".to_string(),
                        "Finanças Corporativas".to_string(),
                        "Mercado de Capitais".to_string(),
                        "Inovação e Empreendedorismo".to_string(),
                        "Gestão de Projetos".to_string(),
                    ],
                ),
            ]),
            sources: vec![ContestSourceRef {
                title: "Matriz Histórica Edital BNDES 2024 (Cesgranrio)".to_string(),
                url: None,
                source_type: "LAST_EDITAL".to_string(),
                updated_at: "2026-06-01".to_string(),
            }],
            latest_diff_summary: None,
        },
    ];

    let count = seed_contests.len();
    contests_coll.insert_many(seed_contests).await?;

    // Semear tópicos do Edital Verticalizado (Oficial e Estimado) com Tronco Comum
    let topics_coll = db.collection::<SyllabusTopicDocument>("editais_topicos");
    let sample_topics = vec![
        SyllabusTopicDocument {
            id: "cpnu_sst_01".to_string(),
            contest_id: "CPNU".to_string(),
            block_name: "Conhecimentos Específicos".to_string(),
            discipline: "Segurança e Saúde do Trabalhador".to_string(),
            title: "Normas Regulamentadoras (NR-01, NR-06, NR-07, NR-09, NR-15 e NR-17)".to_string(),
            order: 1,
            common_trunk_id: Some("tronco_sst_nrs".to_string()),
            estimation: EstimationMetadata {
                is_estimated: false,
                confidence_badge: ConfidenceBadge::High,
                rationale: "Item expresso no Eixo Temático 4 do Edital Oficial.".to_string(),
                reference_exams: vec!["CPNU 2024".to_string(), "AFT 2013".to_string()],
            },
            diff_status: TopicDiffStatus::Confirmed,
        },
        SyllabusTopicDocument {
            id: "cpnu_dt_01".to_string(),
            contest_id: "CPNU".to_string(),
            block_name: "Conhecimentos Específicos".to_string(),
            discipline: "Direito do Trabalho".to_string(),
            title: "Relação de Trabalho vs. Relação de Emprego e Trabalho em Plataformas Digitais".to_string(),
            order: 2,
            common_trunk_id: Some("tronco_dir_trabalho_relacoes".to_string()),
            estimation: EstimationMetadata {
                is_estimated: false,
                confidence_badge: ConfidenceBadge::High,
                rationale: "Tópico novo confirmado na publicação do edital oficial.".to_string(),
                reference_exams: vec!["CPNU 2024".to_string()],
            },
            diff_status: TopicDiffStatus::NewInEdital,
        },
        SyllabusTopicDocument {
            id: "cpnu_fin_01".to_string(),
            contest_id: "CPNU".to_string(),
            block_name: "Conhecimentos Gerais".to_string(),
            discipline: "Finanças Públicas".to_string(),
            title: "Orçamento Público: PPA, LDO, LOA e Ciclo Orçamentário na CF/88".to_string(),
            order: 3,
            common_trunk_id: Some("tronco_financas_orcamento".to_string()),
            estimation: EstimationMetadata {
                is_estimated: false,
                confidence_badge: ConfidenceBadge::High,
                rationale: "Tronco comum entre CPNU e Receita Federal.".to_string(),
                reference_exams: vec!["CPNU 2024".to_string(), "RFB 2023".to_string()],
            },
            diff_status: TopicDiffStatus::Confirmed,
        },
        SyllabusTopicDocument {
            id: "rfb_fin_01".to_string(),
            contest_id: "RFB".to_string(),
            block_name: "Conhecimentos Específicos".to_string(),
            discipline: "Finanças Públicas".to_string(),
            title: "Orçamento Público: PPA, LDO, LOA e Princípios Orçamentários".to_string(),
            order: 1,
            common_trunk_id: Some("tronco_financas_orcamento".to_string()),
            estimation: EstimationMetadata {
                is_estimated: true,
                confidence_badge: ConfidenceBadge::High,
                rationale: "Presente nos últimos 3 editais da Receita Federal (ESAF e FGV).".to_string(),
                reference_exams: vec!["RFB 2023".to_string(), "RFB 2014".to_string()],
            },
            diff_status: TopicDiffStatus::Confirmed,
        },
        SyllabusTopicDocument {
            id: "rfb_trib_01".to_string(),
            contest_id: "RFB".to_string(),
            block_name: "Conhecimentos Específicos".to_string(),
            discipline: "Direito Tributário".to_string(),
            title: "Reforma Tributária (EC 132/2023 e IBS/CBS)".to_string(),
            order: 2,
            common_trunk_id: Some("tronco_tributario_reforma".to_string()),
            estimation: EstimationMetadata {
                is_estimated: true,
                confidence_badge: ConfidenceBadge::Medium,
                rationale: "Tendência forte pós-regulamentação da Reforma Tributária.".to_string(),
                reference_exams: vec!["ISS-RJ 2025".to_string(), "SEFAZ-SP".to_string()],
            },
            diff_status: TopicDiffStatus::NewInEdital,
        },
    ];
    topics_coll.insert_many(sample_topics).await?;

    Ok(count)
}
