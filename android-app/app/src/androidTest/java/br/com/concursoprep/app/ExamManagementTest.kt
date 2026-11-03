package br.com.concursoprep.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.concursoprep.app.domain.exam.*
import br.com.concursoprep.app.ui.screens.*
import br.com.concursoprep.app.ui.theme.ConcursoPrepTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExamManagementTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()

    @Test fun sameCatalogDisciplineCanBeAddedTwiceWithIndependentComponents() {
        var saved: ExamDraft?=null
        rule.setContent { ConcursoPrepTheme { ExamFormBottomSheet(emptyList(),onDismiss={},onSave={saved=it}) } }
        rule.onNodeWithText("Criar do zero").performScrollTo().performClick()
        rule.onNodeWithText("Continuar").performScrollTo().performClick()
        rule.onNodeWithText("Continuar").performScrollTo().performClick()
        rule.onNodeWithText("Buscar em todo o catálogo").performScrollTo().performTextInput("Língua Portuguesa")
        repeat(2) { rule.onNodeWithText("+ Língua Portuguesa").performScrollTo().performClick() }
        rule.onNodeWithText("Salvar na escada").performScrollTo().performClick()
        rule.runOnIdle {
            val subjects=checkNotNull(saved).subjects
            assertEquals(2,subjects.size)
            assertEquals(subjects[0].canonicalSubjectId,subjects[1].canonicalSubjectId)
            assertNotEquals(subjects[0].localSubjectId,subjects[1].localSubjectId)
        }
    }

    @Test fun createsACustomDisciplineWithStageAndGroupBeyondTheTrunkCatalog() {
        var saved: ExamDraft?=null
        rule.setContent {
            val density=androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density,2f)) {
                ConcursoPrepTheme { ExamFormBottomSheet(emptyList(),onDismiss={},onSave={saved=it}) }
            }
        }
        rule.onNodeWithText("Criar do zero").performScrollTo().performClick()
        rule.onNodeWithText("Órgão ou concurso").performScrollTo().performTextInput("Concurso sintético")
        rule.onNodeWithText("Continuar").performScrollTo().performClick()
        rule.onNodeWithText("Continuar").performScrollTo().performClick()
        rule.onNodeWithText("Nome de outra disciplina").performScrollTo().performTextInput("Engenharia de Túneis")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        rule.onNodeWithText("Adicionar disciplina personalizada").performScrollTo().performClick()
        rule.onNodeWithText("Editar disciplina e tópicos").performScrollTo().performClick()
        rule.onNodeWithText("Etapa (ex.: 1ª etapa · Prova objetiva)").performScrollTo().performTextInput("2ª etapa")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        rule.onNodeWithText("Conhecimentos gerais").performScrollTo().performClick()
        rule.onNodeWithText("Novo tópico").performScrollTo().performTextInput("Escavação")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        rule.onNodeWithText("Adicionar tópico").performScrollTo().performClick()
        rule.onNodeWithText("Salvar na escada").performScrollTo().performClick()
        rule.runOnIdle {
            val subject=checkNotNull(saved).subjects.single()
            assertEquals("Engenharia de Túneis",subject.displayName)
            assertEquals("2ª etapa",subject.stage)
            assertEquals("CONHECIMENTOS_GERAIS",subject.category)
            assertEquals("Escavação",subject.topics.single().displayTitle)
            assertTrue(subject.canonicalSubjectId.startsWith("custom-"))
        }
    }

    @Test fun archiveRestoreAndConfirmedDeleteAreReachableInTheManagementArea() {
        val original=ExamCatalog.systemExams().first().copy(shortName="Concurso sintético")
        var exams by mutableStateOf(listOf(original))
        var deletions=0
        rule.setContent { ConcursoPrepTheme {
            ExamManagementScreen(exams,0,onBack={},onCreate={},onEdit={},onArchive={exams=listOf(it.copy(lifecycleStatus="ARQUIVADO"))},onRestore={exams=listOf(it.copy(lifecycleStatus="RUMOR_PREVISTO"))},onDelete={deletions++;exams=emptyList()},onPending={},onUseServer={})
        } }
        rule.onNodeWithText("Arquivar concurso").performScrollTo().performClick()
        rule.onNodeWithText("Nenhum concurso cadastrado.").assertExists()
        rule.onNodeWithText("Arquivados").performClick()
        rule.onNodeWithText("Restaurar concurso").performScrollTo().performClick()
        rule.onNodeWithText("Concursos").performClick()
        rule.onNodeWithText("Excluir concurso").performScrollTo().performClick()
        rule.onNodeWithText("Cancelar").performClick()
        rule.runOnIdle { assertEquals(0,deletions) }
        rule.onNodeWithText("Excluir concurso").performClick()
        rule.onNode(hasText("Excluir concurso") and hasAnyAncestor(isDialog())).performClick()
        rule.runOnIdle { assertEquals(1,deletions);assertTrue(exams.isEmpty()) }
    }
}
