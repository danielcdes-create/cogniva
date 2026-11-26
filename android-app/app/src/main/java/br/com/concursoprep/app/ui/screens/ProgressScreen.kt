package br.com.concursoprep.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.concursoprep.app.domain.exam.DiagnosticEngine
import br.com.concursoprep.app.domain.exam.FatigueStatus
import br.com.concursoprep.app.domain.exam.StrategyAction
import br.com.concursoprep.app.domain.model.DailyHeatmapCellDto
import br.com.concursoprep.app.domain.model.ProgressAnalyticsDto
import br.com.concursoprep.app.domain.model.QuizMode
import br.com.concursoprep.app.ui.theme.CognivaBrandHeader
import br.com.concursoprep.app.ui.theme.LocalExtendedColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Tela "Progresso & Analytics" (Cogniva 2.3):
 * - Prontidão & Confiabilidade Epistêmica sob Escassez (< 15 questões humanas)
 * - Mapa de Transferência Cross-Banca (Conjunto Separado / Transferência Não Mensurada)
 * - Diagnóstico dos 3 Sinais (Resposta × Confiança × Evidência) + Omissões Estratégicas
 * - Calibração Empírica Beta-Binomial & Simulador de Decisão sob Incerteza (VE = 2p − 1)
 * - Modelo Individual Logístico de 300 Minutos p(t) com Controle de Dificuldade
 * - Métricas Voluntárias do Protocolo de Entrada de 60 Segundos
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProgressScreen(
    analytics: ProgressAnalyticsDto,
    onPracticeQuizMode: (QuizMode, String?) -> Unit = { _, _ -> }
) {
    val ext = LocalExtendedColors.current
    val ptBrLocale = remember { Locale.forLanguageTag("pt-BR") }
    var selectedHeatmapCell by remember { mutableStateOf<DailyHeatmapCellDto?>(null) }

    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "progress_header") {
                CognivaBrandHeader(
                    ecosystemProduct = "Cogniva Insights · Evidências",
                    subtitle = "Diagnóstico Metacognitivo, Transferência, Decisão & Curva 300 min"
                )
            }

            // 1. Prontidão & Confiabilidade Epistêmica (Regra < 15 Questões Humanas)
            item(key = "epistemic_readiness") {
                val gap = analytics.examGapSummary
                val minQ = gap.minQuestionsForMastery.coerceAtLeast(DiagnosticEngine.MIN_QUESTIONS_FOR_MASTERY)
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Prontidão & Confiabilidade sob Escassez",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (analytics.historicalCutoffReference > 0) {
                                "Prontidão ponderada ${analytics.readinessIndexScore.toInt()}/100 · Corte de referência: ${analytics.historicalCutoffReference.toInt()}/100"
                            } else {
                                "Prontidão ponderada ${analytics.readinessIndexScore.toInt()}/100 · Sem corte histórico validado"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LinearProgressIndicator(
                            progress = { (analytics.readinessIndexScore.toFloat() / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp)),
                            color = if (analytics.historicalCutoffReference > 0 && analytics.readinessIndexScore >= analytics.historicalCutoffReference) ext.correct else ext.warning
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MiniStatPill(
                                label = "Domínio (≥$minQ + transf.)",
                                value = "${gap.verifiedUnits}",
                                accent = ext.correct,
                                modifier = Modifier.weight(1f)
                            )
                            MiniStatPill(
                                label = "Em evidência (<$minQ)",
                                value = "${gap.evidenceInProgressUnits.coerceAtLeast(gap.consolidatingUnits - gap.activeErrorUnits)}",
                                accent = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            MiniStatPill(
                                label = "Indícios de erro",
                                value = "${gap.activeErrorUnits}",
                                accent = ext.error,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Text(
                            text = "Regra epistêmica: com menos de $minQ questões humanas distintas por unidade, o sistema aponta indícios de erro e progresso sem jamais afirmar domínio pleno. Questões praticadas: ${analytics.totalQuestionsPracticed}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 2. Mapa de Transferência entre Bancas (Conjunto de Avaliação Separado)
            item(key = "cross_board_transfer") {
                val gap = analytics.examGapSummary
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Transferência Independente entre Bancas",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MiniStatPill(
                                label = "Comprovada",
                                value = "${gap.transferVerifiedUnits}",
                                accent = ext.correct,
                                modifier = Modifier.weight(1f)
                            )
                            MiniStatPill(
                                label = "Disponível",
                                value = "${gap.transferAvailableUnits}",
                                accent = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            MiniStatPill(
                                label = "Não mensurada",
                                value = "${gap.transferUnmeasuredUnits}",
                                accent = ext.neutral,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Text(
                            text = "Quando não existe questão humana inédita de outra banca para o conceito, o sistema registra explicitamente 'Transferência não mensurada' em vez de gerar itens sintéticos.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = { onPracticeQuizMode(QuizMode.TRANSFER_PROBE, null) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 46.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Avaliar Transferência em Outra Banca")
                        }
                    }
                }
            }

            // 3. Diagnóstico dos 3 Sinais (Resposta × Confiança × Evidência) & Omissões Estratégicas
            item(key = "three_signal_diagnostics") {
                val sig = analytics.threeSignalSummary
                val cm = analytics.confidenceMatrix
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Diagnóstico Metacognitivo & Matriz de Confiança",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            QuadrantBox(
                                title = "Acerto Firme",
                                subtitle = "Alta confiança (${sig.groundedHits} validados)",
                                count = cm.highConfidenceCorrect,
                                color = ext.correct,
                                modifier = Modifier.weight(1f),
                                onClick = { onPracticeQuizMode(QuizMode.SRS_REVIEW, null) }
                            )
                            QuadrantBox(
                                title = "Domínio Não Confirmado",
                                subtitle = "Acerto c/ fundamento divergente",
                                count = sig.illusoryCompetenceHits,
                                color = ext.warning,
                                modifier = Modifier.weight(1f),
                                onClick = { onPracticeQuizMode(QuizMode.SRS_REVIEW, null) }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            QuadrantBox(
                                title = "Excesso de Confiança",
                                subtitle = "Erro c/ confiança alta (Treinar)",
                                count = cm.highConfidenceError,
                                color = ext.error,
                                modifier = Modifier.weight(1f),
                                onClick = { onPracticeQuizMode(QuizMode.ERROR_NOTEBOOK, null) }
                            )
                            QuadrantBox(
                                title = "Lacuna / Recuperação",
                                subtitle = "${sig.conceptualErrors} conceituais · ${sig.retrievalFailures} esquecimentos",
                                count = cm.lowConfidenceError,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                                onClick = { onPracticeQuizMode(QuizMode.ERROR_NOTEBOOK, null) }
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val savedLabel = String.format(ptBrLocale, "%+.2f pt", sig.omissionNetPointsSaved)
                            Text(
                                text = "Omissões estratégicas em branco: ${sig.strategicOmissions} · Proteção líquida estimada contra penalidade: $savedLabel · Acertos em dúvida: ${cm.lowConfidenceCorrect}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }

            // 3b. Domínio Conceitual Multidimensional (8 Estados) & KPIs Formais Metacognitivos
            analytics.studentLearningAnalytics?.let { sla ->
                item(key = "concept_mastery_8_states") {
                    val cms = sla.conceptMasterySummary
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Domínio Conceitual Multidimensional (8 Estados)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Avalia desempenho, compreensão, transferência CTV, retenção espaçada, calibração metacognitiva, fluência e consistência.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                MiniStatPill(
                                    label = "Consolidado / Dem.",
                                    value = "${cms.consolidatedMastery + cms.demonstratedMastery}",
                                    accent = ext.correct,
                                    modifier = Modifier.weight(1f)
                                )
                                MiniStatPill(
                                    label = "Em desenv. / Inicial",
                                    value = "${cms.developingMastery + cms.emergingKnowledge}",
                                    accent = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f)
                                )
                                MiniStatPill(
                                    label = "Instável / Em risco",
                                    value = "${cms.unstableKnowledge + cms.masteryAtRisk}",
                                    accent = ext.warning,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Text(
                                text = "Consolidado: ${cms.consolidatedMastery} · Demonstrado: ${cms.demonstratedMastery} · Em desenvolvimento: ${cms.developingMastery} · Em risco: ${cms.masteryAtRisk} · Instável: ${cms.unstableKnowledge} · Inicial: ${cms.emergingKnowledge} · Evidência insuficiente: ${cms.insufficientEvidence} · Não avaliado: ${cms.notEvaluated}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (sla.confidenceCalibration.isNotEmpty() || sla.learningKpis.isNotEmpty()) {
                    item(key = "formal_learning_kpis") {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Calibração Temporal (Walk-Forward) & Força da Evidência",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (sla.groundingSummary.coverageSentencePt.isNotBlank()) {
                                    Text(
                                        text = sla.groundingSummary.coverageSentencePt,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                sla.empiricalBrierScore?.let { brier ->
                                    val fmtSplits = buildList {
                                        sla.trueFalseBrierScore?.let { add(String.format(ptBrLocale, "C/E: %.4f", it)) }
                                        sla.multipleChoiceBrierScore?.let { add(String.format(ptBrLocale, "Múltipla escolha: %.4f", it)) }
                                    }.joinToString(" · ")
                                    Text(
                                        text = String.format(
                                            ptBrLocale,
                                            "Brier temporal walk-forward (previsão vs. resultado, não é %% de domínio): %.4f%s · política %s (cold-start: %d)",
                                            brier,
                                            if (fmtSplits.isNotEmpty()) " ($fmtSplits)" else "",
                                            sla.calibrationPolicyVersion,
                                            sla.coldStartPredictionsCount
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } ?: Text(
                                    text = "Brier temporal indisponível (amostra vazia com confiança declarada).",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                sla.confidenceCalibration.forEach { cal ->
                                    val obsStr = cal.observedAccuracyPct?.let { String.format(ptBrLocale, "%.0f%%", it) } ?: "indisponível"
                                    val postStr = String.format(ptBrLocale, "%.0f%%", cal.calibratedPosteriorMean * 100.0)
                                    val headline = cal.coverageSentencePt.takeIf { it.isNotBlank() }
                                        ?: "${cal.labelPt}: ${cal.correct}/${cal.attempts}"
                                    Text(
                                        text = "$headline Taxa observada: $obsStr · Estimativa do modelo: $postStr.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                val ctvSel = sla.ctvAdaptiveSelection
                                if (ctvSel.difficultyTriggered.offersCount > 0 ||
                                    ctvSel.periodicSampling.offersCount > 0 ||
                                    ctvSel.unknownConditionCount > 0
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = "Verificações Complementares por Coorte Adaptativa",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "Dificuldade detectada: ${ctvSel.difficultyTriggered.summaryPt} (ofertas: ${ctvSel.difficultyTriggered.offersCount}, dispensas: ${ctvSel.difficultyTriggered.dismissalsCount}, respostas: ${ctvSel.difficultyTriggered.responsesCount})",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = "Amostragem periódica: ${ctvSel.periodicSampling.summaryPt} (ofertas: ${ctvSel.periodicSampling.offersCount}, dispensas: ${ctvSel.periodicSampling.dismissalsCount}, respostas: ${ctvSel.periodicSampling.responsesCount})",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (ctvSel.unknownConditionCount > 0) {
                                                Text(
                                                    text = "Registros históricos com condição desconhecida preservados: ${ctvSel.unknownConditionCount}.",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                                sla.learningKpis.take(6).forEach { kpi ->
                                    val valStr = kpi.value?.let { v ->
                                        if (kpi.unit == "%") String.format(ptBrLocale, "%.1f%%", v)
                                        else String.format(ptBrLocale, "%.2f %s", v, kpi.unit).trim()
                                    } ?: "Amostra pendente"
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = kpi.labelPt,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Text(
                                                    text = "$valStr (n=${kpi.sampleSizeN})",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            if (kpi.interpretiveLimitation.isNotBlank()) {
                                                Text(
                                                    text = kpi.interpretiveLimitation,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. Calibração Beta-Binomial & Simulador de Decisão sob Incerteza (VE = 2p - 1)
            if (analytics.strategyGuidance.isNotEmpty()) {
                item(key = "calibration_strategy") {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Decisão sob Incerteza & Pontuação Líquida",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            val penaltyText = if (analytics.wrongAnswerPenalty > 0.0) {
                                val pStarPct = (analytics.wrongAnswerPenalty / (1.0 + analytics.wrongAnswerPenalty) * 100.0).toInt()
                                "Penalidade -${analytics.wrongAnswerPenalty} por erro · VE = (1+λ)p − λ (p = 55% gera +0,10 pt em +1/−1; p* = $pStarPct%). Em item binário C/E, 48% em uma opção implica 52% na complementar."
                            } else {
                                "Sem penalidade por erro na grade ativa · Responder maximiza a pontuação esperada e a chance de atingir a meta."
                            }
                            Text(
                                text = penaltyText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            analytics.strategyGuidance.forEach { bandItem ->
                                val bandLabel = when (bandItem.confidenceBand) {
                                    1 -> "Baixa / Chute (1)"
                                    2 -> "Média / Dúvida (2)"
                                    else -> "Alta / Certeza (3)"
                                }
                                val evStr = String.format(ptBrLocale, "%+.2f pt", bandItem.decision.expectedValue)
                                val recommendOmit = bandItem.decision.recommendedAction == StrategyAction.OMIT
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "$bandLabel: ${(bandItem.calibratedMean * 100.0).toInt()}% (VE $evStr)",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = if (recommendOmit) "Omitir" else "Responder",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = if (recommendOmit) ext.warning else ext.correct
                                    )
                                }
                            }

                            if (analytics.examScenarios.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Cenários de Prova (Confiança Média vs. Objetivo de Pontuação):",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                analytics.examScenarios.forEach { sc ->
                                    val dec = sc.decisionAtMediumConfidence
                                    val pAns = String.format(ptBrLocale, "%.0f%%", dec.probGoalIfAnswer * 100.0)
                                    val pOm = String.format(ptBrLocale, "%.0f%%", dec.probGoalIfOmit * 100.0)
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = sc.label,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Text(
                                                    text = "P(meta|Resp) $pAns vs. P(meta|Omitir) $pOm",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Text(
                                                text = if (dec.recommendedAction == StrategyAction.OMIT) "Omitir" else "Responder",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = if (dec.recommendedAction == StrategyAction.OMIT) ext.warning else ext.correct
                                            )
                                        }
                                    }
                                }
                            }

                            OutlinedButton(
                                onClick = { onPracticeQuizMode(QuizMode.STRATEGY_BATTERY, null) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 46.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Treinar no Simulador de Decisão (8–12 min)")
                            }
                        }
                    }
                }
            }

            // 5. Modelo Individual de 300 Minutos p(t) com Controle de Dificuldade
            item(key = "fatigue_endurance") {
                val fatigue = analytics.fatigueReport
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Curva Individual de 300 Minutos · p(t)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        when (fatigue.status) {
                            FatigueStatus.INSUFFICIENT_EVIDENCE -> {
                                Text(
                                    text = "Calibração longitudinal pendente (${fatigue.qualifyingSessions}/${fatigue.requiredSessions} simulados longos sem interrupção · ${fatigue.qualifyingAttempts}/${fatigue.requiredAttempts} itens cronometrados). Simulações matemáticas não substituem provas prolongadas reais com controle de dificuldade.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            FatigueStatus.FATIGUE_DETECTED -> {
                                val dropPct = String.format(ptBrLocale, "%+.1f%%", (fatigue.shrunkAccuracyDelta ?: 0.0) * 100.0)
                                val lamStr = String.format(ptBrLocale, "%+.2f", fatigue.estimatedLambda ?: 0.0)
                                Text(
                                    text = "Queda controlada por dificuldade detectada ($dropPct · λᵢ = $lamStr em ${fatigue.qualifyingSessions} simulados).",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ext.warning
                                )
                            }
                            FatigueStatus.STABLE_ENDURANCE -> {
                                val lamStr = String.format(ptBrLocale, "%+.2f", fatigue.estimatedLambda ?: 0.0)
                                Text(
                                    text = "Resistência estável ao longo da prova confirmada (λᵢ = $lamStr em ${fatigue.qualifyingSessions} simulados com controle de dificuldade).",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ext.correct
                                )
                            }
                            FatigueStatus.WARMUP_GAIN_DETECTED -> {
                                val gainPct = String.format(ptBrLocale, "%+.1f%%", (fatigue.shrunkAccuracyDelta ?: 0.0) * 100.0)
                                val lamStr = String.format(ptBrLocale, "%+.2f", fatigue.estimatedLambda ?: 0.0)
                                Text(
                                    text = "Efeito positivo de aquecimento detectado ao longo da prova ($gainPct · λᵢ = $lamStr em ${fatigue.qualifyingSessions} simulados).",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ext.correct
                                )
                            }
                        }

                        if (fatigue.curve300m.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                fatigue.curve300m.forEach { pt ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                    ) {
                                        Text(
                                            text = "${pt.minute}m: ${(pt.predictedAccuracy * 100.0).toInt()}%",
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 6. Métricas Voluntárias do Protocolo 60s + Consistência (Sem Cobrança de Sequência)
            item(key = "voluntary_habit_metrics") {
                val em = analytics.entryMetrics
                val startRatePct = if (em.impressionsCount > 0) {
                    ((em.voluntaryStartsCount.toDouble() / em.impressionsCount.toDouble()) * 100.0).coerceAtMost(100.0).toInt()
                } else {
                    0
                }
                val firstCompletionPct = if (em.voluntaryStartsCount > 0) {
                    ((em.firstQuestionCompletedCount.toDouble() / em.voluntaryStartsCount.toDouble()) * 100.0).coerceAtMost(100.0).toInt()
                } else {
                    0
                }
                val dayFormatter = remember(ptBrLocale) { DateTimeFormatter.ofPattern("dd/MM (EEE)", ptBrLocale) }
                val recentCells = remember(analytics.activityHeatmap) { analytics.activityHeatmap.takeLast(14) }

                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Hábito Voluntário · Protocolo de 60s",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MiniStatPill(
                                label = "Início voluntário",
                                value = if (em.impressionsCount > 0) "$startRatePct%" else "—",
                                accent = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            MiniStatPill(
                                label = "1ª questão concluída",
                                value = if (em.voluntaryStartsCount > 0) "$firstCompletionPct%" else "${em.firstQuestionCompletedCount}",
                                accent = ext.correct,
                                modifier = Modifier.weight(1f)
                            )
                            MiniStatPill(
                                label = "Retornos espontâneos",
                                value = "${em.spontaneousReturnDaysCount}",
                                accent = ext.correct,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Text(
                            text = "Dias com estudo voluntário: ${em.activeStudyDaysCount} · Pausas conscientes registradas sem penalidade: ${em.consciousRestDaysCount}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                        ) {
                            recentCells.forEach { cell ->
                                val alpha = (cell.questionsCount.toFloat() / 25f).coerceIn(0.18f, 1f)
                                val isSelected = selectedHeatmapCell?.dateYmd == cell.dateYmd
                                val formattedDate = runCatching { LocalDate.parse(cell.dateYmd).format(dayFormatter) }.getOrDefault(cell.dateYmd)
                                val formattedAcc = String.format(ptBrLocale, "%.0f%%", cell.accuracyPct)
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else ext.correct.copy(alpha = alpha),
                                    modifier = Modifier
                                        .size(24.dp)
                                        .semantics {
                                            role = Role.Button
                                            selected = isSelected
                                            contentDescription = "$formattedDate: ${cell.questionsCount} questões respondidas, $formattedAcc de acerto"
                                        }
                                        .clickable { selectedHeatmapCell = cell }
                                ) {
                                    Box(modifier = Modifier.fillMaxSize())
                                }
                            }
                        }
                        selectedHeatmapCell?.let { cell ->
                            val formattedDate = runCatching { LocalDate.parse(cell.dateYmd).format(dayFormatter) }.getOrDefault(cell.dateYmd)
                            val formattedAcc = String.format(ptBrLocale, "%.1f%%", cell.accuracyPct)
                            Text(
                                text = "$formattedDate (${cell.dateYmd}): ${cell.questionsCount} questões ($formattedAcc acerto)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // 7. Raio-X por Disciplina (com Amostra Distinta X/15)
            item(key = "discipline_header") {
                Text(
                    text = "Evidência Acumulada por Disciplina",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            items(
                items = analytics.disciplinesSortedByWeakness,
                key = { it.discipline }
            ) { item ->
                val barColor = when {
                    item.accuracyPct < 60.0 -> ext.error
                    item.accuracyPct < 75.0 -> ext.warning
                    else -> ext.correct
                }
                val sampleText = if (item.distinctQuestions > 0) {
                    "${item.distinctQuestions}/${DiagnosticEngine.MIN_QUESTIONS_FOR_MASTERY} questões distintas"
                } else {
                    "${item.masteredQuestions} consolidadas"
                }
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPracticeQuizMode(QuizMode.STANDARD, item.discipline) }
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.discipline,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${item.accuracyPct.toInt()}%",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = barColor
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { (item.accuracyPct.toFloat() / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = barColor
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${item.totalAttempts} tentativas · $sampleText",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Praticar →",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniStatPill(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = accent.copy(alpha = 0.12f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = accent
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun QuadrantBox(
    title: String,
    subtitle: String,
    count: Int,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.14f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = "$count",
                style = MaterialTheme.typography.headlineMedium,
                color = color
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
