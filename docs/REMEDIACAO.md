# Recuperação da auditoria de 2026-11-01

Escopo autorizado: C01–C39 de `auditoria/2026-11-01/AUDITORIA-COGNIVA.md`.
As notas renomeadas pelo usuário e o histórico de produção são preservados.

## Execução

- [ ] Identidade autenticada, isolamento e preservação de filas por proprietário.
- [ ] Tentativas duráveis, validação autoritativa, idempotência e ACK por evento.
- [ ] Concurso persistido, versionado e sincronizado nos dois sentidos.
- [ ] Contratos únicos de domínio, cobertura, métricas, simulado e SRS.
- [ ] Sessão persistente, operações assíncronas e reporte durável.
- [ ] TLS/distribuição, CI com gates, promoção/rollback e documentação.
- [ ] Suítes atuais, cenários adversariais e build Android verificados.

Não executar os antigos probes como testes de aceite: eles afirmam o comportamento defeituoso. Os cenários serão incorporados à suíte com os resultados corrigidos. Testes de banco usam apenas uma instância isolada/sintética.

## Estado intermediário (trabalho em andamento)

- Branch de trabalho: `codex/auditoria-convergencia`; sem publicação de código nem escrita no Atlas até este ponto.
- Backend 1.4.0: sessão verificada, vínculo histórico por código de uso único, identidade nativa própria; escrita de evento/projeção/recibo no mesmo documento, compare-and-swap e preservação de campos legados; concurso persistido e versionado por usuário. A suíte de 22 testes passou, incluindo MongoDB real isolado em loopback 27048, falha de escrita injetada, retry, concorrência e restart.
- Android 1.4.0/build 7: migração Room 2→3 aditiva, pendências legadas em revisão, banco separado por conta; nova sincronização, cache semântico, histórico local verdadeiro e sessão persistida. Compilação e novos testes ainda em validação; não declarar esta fase concluída.
- Catálogo compartilhado em `shared/canonical-catalog.json`, com modelos explicitamente não oficiais e sem datas/cortes inventados. Não se remapeiam questões antigas por substring.
- Notas de arquitetura e de screenshot lidas integralmente após orientação do usuário. Preservar Paparazzi 1.3.5/compileSdk 35/DeviceConfig.PIXEL: geometrias arbitrárias quebram o renderer. C36 será resolvido pela descrição honesta da cobertura e prova no emulador, não por repetir esse caminho quebrado.
- Temurin 21 portátil verificado por SHA-256 em `%TEMP%/cogniva-temurin21`; caminho/versão em `remediation-validation/jdk-runtime.json`. JBR 25 não é usado nos gates.
- Mongo de teste portátil, sem serviço instalado e sem conexão com Atlas, metadados em `remediation-validation/mongo-runtime.json`; PID em `mongo.pid`. Encerrar somente esse processo ao concluir.
- A auditoria histórica possui cópia dos 74 arquivos auditados em `auditoria/2026-11-01/source-snapshot`, todos conferidos com os hashes da captura original.
- SSH existente para root@198.199.65.103 funciona com verificação de host. Somente inspeção realizada: projeto Compose `apps`, `/opt/apps/docker-compose.yml`, outros serviços FIN/Asbuildo/Spamshield presentes. Não sobrescrever esse Compose global com placeholders do repositório.
- GitHub CLI autenticado no repositório; há segredos DO_HOST/DO_USER/DO_SSH_KEY. Não executado push/deploy ainda.
- HTTPS por IP é suportado pelo Let's Encrypt/Certbot >=5.4 com `--ip-address` e perfil `shortlived`; usuário forneceu IP público. Preparar renovação automática e bootstrap sem build na VPS. Não há certbot instalado no servidor na inspeção.
- Restam integração Android, isolamento/edição de sessões e pendências, política completa de nota/grade, rate limits/tutores, CI/promoção/rollback/assinatura/TLS e documentação final por C01–C39.

## Validação local antes do CI

- Rust: 23 testes passaram no checkout atual, inclusive router real com Mongo sintético dedicado: ACK após falha, retry/concorrrência, ocorrência, isolamento, restart, CAS/revisões de concurso, publicação idempotente, seleção pela grade, métricas de 90 dias, dry-run/aplicação canônica, vínculo e renovação.
- Android: 24 testes JVM de repositório/domínio passaram; build release e compilação dos instrumentados concluídos; lint sem erros (avisos de versões/target preservados conforme as notas).
- Visual: sete variantes passaram em duas execuções reais; controle negativo com PNG 1×1 válido falhou na comparação; golden restaurado ao SHA-256 registrado em `remediation-validation/visual-gate.json`. Só Font Scale 2.0 recebeu atualização revisada: diferença de dois pixels na altura/posição do CTA, sem mudar geometria do renderer. O golden deve permanecer limpo após commit.
- Backup privado somente leitura concluído: 618 questões, 15 documentos/27 eventos históricos, quatro perfis. Hash protegido em `production-before.json`. Parser atual validou todos os documentos tipados; 612 questões têm gabarito interpretável. Seis inválidas e zero vínculos canônicos confirmados são preservados, sem preencher respostas/tópicos por inferência.
- HTTPS real instalado, testado da máquina local e na VPS, com timer horário de renovação. Nginx usa IPs públicos para preservar o listener 443 do Tailscale.
- Chave privada de release e prova de rotação preservadas fora do repositório, com Secrets de CI configurados. API 26–27 mantém limitação do assinante antigo; API 28+ usa rotação.
- Pipeline preparado com gates, bundle imutável, imagem por digest/tamanho medido, ensaio de rollback, gate de manutenção durante migração de protocolo e alteração somente do serviço Cogniva. Actionlint passou. CI/emulador/promoção ainda pendentes; não declarar entrega integral.

## Primeiro CI e fechamento dos casos de conflito

- Run `41339873013`, revisão `3be5bfb`: Android/JVM/lint/visuais passaram e o emulador API 35/Android 15 passou três testes reais (insets, migração Room 2→3 e sessão/reabertura). Log: `ci-emulator-first.log`; geometria 1440×3088/500dpi, cutout ativo; top bar 136 ≥ inset 136, CTA 2975 ≤ 3088−75.
- O gate backend falhou por conectividade ao Mongo sintético do job. Corrigidos bind explícito, cache de memória e espera de ping; nenhuma imagem foi promovida nesse run.
- Complementos C15/C26/C30: tronco comum offline considera todos os concursos ativos e seus tópicos; grade arquivada não volta à fila; bancas desconhecidas não são tratadas como filtro confirmado. Exportação inclui edições de concurso e cópias de conflito. Há ação explícita para preservar a edição antes de carregar o servidor; ACK atrasado mantém a edição feita durante o envio. Mudanças em fila/erro/concursos atualizam a UI.
- Suíte local posterior: 27 testes JVM de domínio/repositório e 23 Rust passaram. Emulador de CI agora aponta explicitamente a API para loopback indisponível, para não criar contas nem sincronizar dados contra produção. A assinatura rotacionada conserva somente a capacidade necessária de atualizar dados instalados; privilégios do assinante antigo foram revogados.
- Novo CI na revisão seguinte ainda precisa passar antes da promoção. Build e promoção continuam bloqueados por qualquer gate vermelho.
