# Bot do Telegram para quiz de concursos públicos com recursos de IA.

import telebot
from telebot.types import Message
from telebot import types, apihelper
from telebot.apihelper import ApiTelegramException
import openai
import google.generativeai as genai
import pymongo
from pymongo import ReturnDocument
from pymongo.errors import PyMongoError
import json
from bson.objectid import ObjectId
from bson.errors import InvalidId
import logging
import re
import time
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
import schedule
import random
import uuid
import hashlib
import requests
from typing import List, Optional, Tuple, Dict
import math
import urllib.parse
from textblob import TextBlob
from difflib import SequenceMatcher
from enum import Enum

logging.basicConfig(filename='bot_log.log', level=logging.DEBUG, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

client = pymongo.MongoClient(os.getenv("MONGODB_URI", "mongodb://localhost:27017"))
db = client["CNU"]

bot = telebot.TeleBot(os.getenv("TELEGRAM_BOT_TOKEN", ""))

openai.api_key = os.getenv("OPENAI_API_KEY", "")

GENAI_API_KEY = os.getenv("GEMINI_API_KEY", "")
genai.configure(api_key=GENAI_API_KEY)
generation_config = {
    "candidate_count": 1,
    "temperature": 0.2,
    "top_p": 0.7,
    "top_k": 10,
    "max_output_tokens": 2048,
    "stop_sequences": [],
}
safety_settings = [
    {"category": "HARM_CATEGORY_HARASSMENT", "threshold": "BLOCK_NONE"},
    {"category": "HARM_CATEGORY_HATE_SPEECH", "threshold": "BLOCK_NONE"},
    {"category": "HARM_CATEGORY_SEXUALLY_EXPLICIT", "threshold": "BLOCK_NONE"},
    {"category": "HARM_CATEGORY_DANGEROUS_CONTENT", "threshold": "BLOCK_NONE"},
]

system_instruction = '''
Você é um especialista em concursos públicos, com vasta experiência em elaborar materiais didáticos. Sua tarefa é analisar questões de múltipla escolha, fornecendo explicações claras e concisas para cada alternativa em formato Markdown.
Seu objetivo é ajudar os candidatos a entender a lógica por trás da pergunta e de cada opção de resposta, capacitando-os a identificar a resposta correta com base em seus conhecimentos.
Siga estas diretrizes para elaborar a análise:

Formato:
    Receberá um JSON contendo a pergunta ("question") e uma lista de alternativas ("choices").
    Sua resposta deve estar em formato Markdown.
Conteúdo da Análise:
    Primeiro faça um pequeno overview do cerne do texto da questão:
    Explique cada alternativa individualmente:
        Comece com "✅:" se a alternativa for verdadeira.
        Comece com "❌:" se a alternativa for falsa.
    Seja conciso e objetivo: Use frases curtas e evite informações irrelevantes.
    Foco na lógica: Explique por que a alternativa é verdadeira ou falsa em relação ao enunciado da questão.
    Complemente com exemplos quando possível:  Exemplos relevantes facilitam a compreensão.
    Utilize Markdown para formatar a resposta: Negrito para destacar pontos importantes, quebras de linha para melhor organização, principalmente entre o texto da alternativa e sua explicação.
    Não utilize marcadores de letras ou números: As alternativas serão embaralhadas, então não as referencie por A, B, C, 1, 2, 3, etc.
Linguagem:
    Utilize uma linguagem clara e acessível, adequada para estudantes.
    Mantenha um tom didático e encorajador.

Exemplo de Input JSON:

```json
{
  "question": "Qual mercado de trabalho se caracteriza por atividades sem contratos formais e menor proteção aos trabalhadores?",
  "choices": [
    "Mercado de trabalho informal",
    "Mercado de trabalho formal",
    "Mercado de trabalho terceirizado",
    "Mercado de trabalho digital",
    "Mercado de trabalho global"
  ]
}

**O mercado de trabalho é o espaço onde ocorrem as relações entre empregadores e empregados, regidas por leis e normas que visam proteger os direitos dos trabalhadores.**\n\n

**✅: Mercado de trabalho informal:**\n
Caracterizado por relações de trabalho sem registro formal, contratos ou carteira assinada. Os trabalhadores nesse mercado geralmente não possuem acesso a benefícios como seguro social, FGTS, férias remuneradas e licenças médicas. Maior vulnerabilidade à exploração e precarização das condições de trabalho.
Exemplos: trabalhadores autônomos, vendedores ambulantes, diaristas, trabalhadores domésticos sem carteira assinada, etc.\n\n

**❌: Mercado de trabalho formal:**\n
Caracterizado por relações de trabalho com registro formal, contrato e carteira assinada. Os trabalhadores têm acesso a benefícios como seguro social, FGTS, férias remuneradas e licenças médicas. Maior proteção legal e melhores condições de trabalho.
Exemplos: funcionários públicos, trabalhadores com carteira assinada em empresas privadas, etc.\n\n

**❌: Mercado de trabalho terceirizado:**\n
Caracterizado pela contratação de empresas para realizar serviços específicos para outra empresa (tomadora de serviços). Os trabalhadores terceirizados podem estar tanto no mercado formal quanto no informal, dependendo da empresa contratante e da forma de contratação. Nem todos os trabalhadores terceirizados estão em situação de informalidade.\n\n

**❌: Mercado de trabalho digital:**\n
Caracterizado pelas atividades realizadas por meio de plataformas digitais ou aplicativos. As relações de trabalho nesse mercado podem ser tanto formais quanto informais, variando de acordo com a plataforma ou aplicativo e a forma de contratação. Nem todas as atividades no mercado de trabalho digital configuram informalidade.\n\n

**❌: Mercado de trabalho global:**\n
Caracterizado pela livre circulação de trabalhadores entre diferentes países. As relações de trabalho nesse mercado podem ser tanto formais quanto informais, dependendo do país e da forma de contratação. Nem todas as situações de trabalho global configuram informalidade.\n
'''

model = genai.GenerativeModel(
    model_name="gemini-1.5-flash-latest",
    generation_config=generation_config,
    safety_settings=safety_settings,
    system_instruction=system_instruction
)

# Lista de tipos de problemas válidos que podem ser reportados em uma questão.
# Cada item na lista representa uma categoria específica de problema que um usuário pode encontrar em uma questão ou enunciado.

VALID_PROBLEM_LIST = [
    "Questão com Erros de Conteúdo",
    "Questão com Formatação Ruim",
    "Questão Mal Formulada",
    "Questão com Erros de Gramática ou Ortografia",
    "Enunciado Mal Formulado",
    "Enunciado c/ Erros de Conteúdo",
    "Enunciado c/ Formatação Ruim",
    "Enunciado c/ Erros de Gramática ou Ortografia",
    "Alternativas Mal Formuladas",
    "Alternativas Corretas em Duplicidade",
    "Alternativas Muito Fáceis",
    "Alternativas c/ Erros de Conteúdo",
    "Alternativas c/ Formatação Ruim",
    "Alternativas c/ Erros de Gramática ou Ortografia",
    "Explicação c/ Formatação Ruim",
    "Explicação Incompleta",
    "Explicação Divergente c/ Alternativa Correta",
    "Explicação c/ Erros de Conteúdo",
    "Explicação c/ Erros de Gramática ou Ortografia",
    "Outro Problema com a Questão"
]


class EXAMS_LIST(Enum):
    CPNU = "CPNU"
    BNDES = "BNDES"
    RFB = "RFB"
    GERAL = "Geral"

# Lista de opções de nível de confiança para o usuário escolher
CONFIDENCE_LEVELS = [
    "Nenhuma Ideia 😔", 
    "Chute 🤔", 
    "Sei Mais ou Menos 😅", 
    "Dominei! 😎"
]

# Mapeamento dos níveis de confiança para valores numéricos
CONFIDENCE_LEVEL_VALUES = {
    "Nenhuma Ideia 😔": 0,
    "Chute 🤔": 1,
    "Sei Mais ou Menos 😅": 2,
    "Dominei! 😎": 3
}

# Dicionário que mapeia as categorias de exams para suas respectivas subjects.
# As chaves representam as categorias de exams, como "Conhecimentos Gerais" e "Conhecimentos Específicos".
# Os valores são listas de subjects relacionadas a cada categoria.

EXAMS_SUBJECTS_CPNU = {
    "Conhecimentos Gerais": [
        "Políticas Públicas",
        "Desafios do Estado de Direito",
        "Ética e Integridade",
        "Diversidade e Inclusão na Sociedade",
        "Administração Pública Federal",
        "Finanças Públicas"
    ],
    "Conhecimentos Específicos": [
        "Gestão Governamental e Governança Pública",
        "Políticas Públicas",
        "Sociologia e Psicologia Aplicadas ao Trabalho",
        "Segurança e Saúde do Trabalhador",
        "Direito do Trabalho",
    ]
}

EXAMS_SUBJECTS_RFB = {
    "Conhecimentos Específicos": [
        "Contabilidade Geral",
        "Contabilidade Pública",
        "Contabilidade Tributária",
        "Direito Tributário",
        "Economia",
        "Estatística",
        "Finanças Públicas",
        "Informática",
        "Legislação Tributária",
        "Matemática Financeira"
    ],

    "Conhecimentos Gerais": [
        "Direito Administrativo",
        "Direito Civil",
        "Direito Constitucional",
        "Direito Internacional",
        "Direito Tributário"
    ]
}

# Dicionário que mapeia as categorias de exams do BNDES para suas respectivas subjects.
# As chaves representam as categorias de exams, como "Conhecimentos Básicos", "Conhecimentos Transversais" e "Conhecimentos Específicos".
# Os valores são listas de subjects relacionadas a cada categoria.

EXAMS_SUBJECTS_BNDES = {
    "Conhecimentos Básicos": [
        "Língua Portuguesa",
        "Língua Inglesa"
    ],
    "Conhecimentos Transversais": [
        "Papel do BNDES no desenvolvimento brasileiro",
        "Clima, Sustentabilidade e Responsabilidade Socioambiental e Climática",
        "Princípios de análise de dados e informações",''
        "Diversidade e Inclusão"
    ],
    "Conhecimentos Específicos": [
        "Probabilidade e Estatística",
        "Fundamentos Econômicos",
        "Matemática Financeira",
        "Contabilidade Geral, Gerencial e de Custos",
        "Administração e Gestão Estratégica",
        "Análise Setorial",
        "Sustentabilidade e Mudanças Climáticas",
        "Análise e Avaliação de Projetos",
        "Finanças Corporativas",
        "Mercado de Capitais",
        "Inovação e Empreendedorismo",
        "Gestão de Projetos"
    ]
}

all_subjects = set()
for subjects in EXAMS_SUBJECTS_CPNU.values():
    all_subjects.update(subjects)
for subjects in EXAMS_SUBJECTS_BNDES.values():
    all_subjects.update(subjects)
for subjects in EXAMS_SUBJECTS_RFB.values():  # Adicione esta linha
    all_subjects.update(subjects)
all_subjects = list(all_subjects)

# Conjunto que reúne todas as subjects de todas as exams em `EXAMS_SUBJECTS_CPNU` e `EXAMS_SUBJECTS_BNDES`.
# `subjects` será um conjunto único (sem duplicatas) de todas as subjects listadas.
subjects = set(all_subjects)

# Lista que combina todas as chaves (nomes das exams) de `EXAMS_SUBJECTS_CPNU` e `EXAMS_SUBJECTS_BNDES`.
# `exams` será uma lista contendo os nomes de todas as exams disponíveis.
exams = list(EXAMS_SUBJECTS_CPNU.keys()) + list(EXAMS_SUBJECTS_BNDES.keys()) + list(EXAMS_SUBJECTS_RFB.keys())

# Dicionário que mapeia subjects às suas respectivas proporções em um simulado para um concurso específico.
# As chaves representam os nomes das subjects, e os valores representam a proporção de questões daquela disciplina no simulado.
# A soma de todas as proporções deve ser igual a 1, conforme a verificação do assert.

SIMULATION_PROPORTIONS_CPNU = {
    "Administração Pública Federal": 0.04,
    "Desafios do Estado de Direito": 0.05,
    "Ética e Integridade": 0.05,
    "Diversidade e Inclusão na Sociedade": 0.04,
    "Finanças Públicas": 0.04,
    "Políticas Públicas": 0.105,
    "Gestão Governamental e Governança Pública": 0.075,
    "Sociologia e Psicologia Aplicadas ao Trabalho": 0.15,
    "Segurança e Saúde do Trabalhador": 0.225,
    "Direito do Trabalho": 0.225
}

SIMULATION_PROPORTIONS_BNDES = {
    "Probabilidade e Estatística": 0.046,
    "Fundamentos Econômicos": 0.046,
    "Matemática Financeira": 0.046,
    "Contabilidade Geral, Gerencial e de Custos": 0.088,
    "Administração e Gestão Estratégica": 0.0799999999999998,  # Nota: valor próximo de 0.08, pode haver arredondamento acumulado
    "Análise Setorial": 0.046,
    "Sustentabilidade e Mudanças Climáticas": 0.088,
    "Análise e Avaliação de Projetos": 0.088,
    "Finanças Corporativas": 0.046,
    "Mercado de Capitais": 0.046,
    "Inovação e Empreendedorismo": 0.046,
    "Gestão de Projetos": 0.088,
    "Papel do BNDES no desenvolvimento brasileiro": 0.046,
    "Clima, Sustentabilidade e Responsabilidade Socioambiental e Climática": 0.046,
    "Princípios de análise de dados e informações": 0.046,
    "Diversidade e Inclusão": 0.016,
    "Língua Portuguesa": 0.046,
    "Língua Inglesa": 0.046,
}

SIMULATION_PROPORTIONS_RFB = {
    "Contabilidade Geral": 0.07,
    "Contabilidade Pública": 0.07,
    "Contabilidade Tributária": 0.07,
    "Direito Administrativo": 0.07,
    "Direito Civil": 0.07,
    "Direito Constitucional": 0.07,
    "Direito Internacional": 0.05,
    "Direito Tributário": 0.14,
    "Economia": 0.07,
    "Estatística": 0.07,
    "Finanças Públicas": 0.07,
    "Informática": 0.05,  # Reduzido de 0.06 para 0.05
    "Legislação Tributária": 0.07,
    "Matemática Financeira": 0.06
}

# Verificação da soma
#total = sum(SIMULATION_PROPORTIONS_Receita_Federal.values())
#print(f"Soma total: {total}")

# Assertion para garantir que a soma seja 1
#assert abs(total - 1) < 1e-6, "A soma das proporções deve ser igual a 1"

DIAS_POR_MES = 30.44

# Inteiro que define o objetivo padrão de questões a serem respondidas em um quiz.
# Esta variável pode ser usada para definir um alvo ou meta no quiz.
QUESTIONS_DAY_TARGET = 50

# Lista de opções de quantidade de questões que podem ser selecionadas pelo usuário.
# Cada item é uma string que representa uma quantidade específica de questões para o quiz.
QUESTIONS_NUMBER = ["1 Questão","3 Questões", "5 Questões", "10 Questões", "20 Questões", "40 Questões", "60 Questões", "100 Questões"]

# Dicionário que armazena informações sobre a disciplina e o progresso do quiz de cada usuário.
# A chave é o user_id e o valor é outro dicionário com detalhes como a disciplina, quantidade de questões respondidas, etc.
user_quiz_state = {}

# Dicionário que armazena o tempo de início da última questão para cada usuário.
# A chave é o user_id e o valor é o timestamp (tempo em que a questão foi iniciada).
question_start_time = {}

# Variável que pode armazenar a última questão gerada ou exibida para um usuário.
# Utilizada para rastrear o progresso ou verificar consistência.
last_question = None

# Dicionário que armazena a última questão respondida por cada usuário.
# A chave é o user_id e o valor é a última questão respondida.
last_answered_question = {}

@bot.message_handler(commands=["sobre"])
def cmd_about(message):
    """Exibe informações sobre o bot."""
    user_id = message.chat.id
    texto_sobre = """
    👋 Olá! Sou um bot de quiz focado em concursos públicos, criado para ajudar você a testar seus conhecimentos e aprimorar seus estudos de forma interativa. 

    **Recursos:**

    📚 Abrange uma variedade de disciplinas e provas.
    ❓ Questões desafiadoras de diversas fontes.
    📊 Acompanhamento do seu progresso e desempenho.
    🤖 Interface amigável e fácil de usar.

    **Comandos:**

    /start - Inicia o bot e apresenta as opções de quiz.
    /report - Reporta um problema com a última questão respondida.
    /sobre - Exibe estas informações sobre o bot.

    **Desenvolvedor:** Daniel Caires

    **Feedback:**

    Sua opinião é muito importante! Use o comando /report para relatar qualquer problema encontrado nas questões. 
    Para elogios, sugestão de melhorias ou qualquer outro tópico, veja o comando /ajuda. 😄
    """
    util_send_message_with_retry(user_id, texto_sobre, parse_mode="Markdown")
    logger.debug(f"Informações sobre o bot exibidas para o usuário {user_id}.")

@bot.message_handler(commands=["ajuda"])
def cmd_help(message):
    """Exibe informações de ajuda sobre o bot."""
    user_id = message.chat.id
    texto_ajuda = """
    Precisa de ajuda? 🤔 Estou aqui para te auxiliar!

    **Comandos:**

    /start - Iniciar Sessão de Estudo: Comece a responder às questões.
    /interromper - Interromper Sessão de Estudo: Encerre a sessão atual.
    /report - Reportar Problema em Questão Anterior: Avise-nos sobre qualquer erro na questão.
    /cancelreport - Cancelar Reporte de Problema: Cancele um reporte em andamento.
    /sobre - Sobre o Bot: Saiba mais sobre mim.
    /ajuda - Obter Ajuda: Exibe esta mensagem de ajuda.

    **Em caso de dúvidas adicionais, entre em contato com o desenvolvedor:**

    Daniel Caires
    daniel.caires@yahoo.com
    https://github.com/dancaires
    """
    util_send_message_with_retry(user_id, texto_ajuda, parse_mode="Markdown")
    logger.debug(f"Informações de ajuda exibidas para o usuário {user_id}.")

def util_create_markup(buttons, row_width=1, one_time_keyboard=True, include_cancel=False):
    """
    Cria um teclado personalizado para interação com o bot.

    Esta função cria um teclado com botões personalizados baseado em uma lista de strings.
    Pode ser configurado para ter múltiplas colunas e um botão de cancelamento.

    Args:
        buttons (list): Lista de strings representando os textos dos botões.
        row_width (int): Número de botões por linha (padrão é 1).
        one_time_keyboard (bool): Define se o teclado será ocultado após o uso (padrão é True).
        include_cancel (bool): Se True, inclui um botão de "Cancelar" ao final (padrão é False).

    Returns:
        types.ReplyKeyboardMarkup: Objeto de teclado personalizado.
    """
    markup = types.ReplyKeyboardMarkup(row_width=row_width, one_time_keyboard=one_time_keyboard)
    for button in buttons:
        markup.add(types.KeyboardButton(button))
    if include_cancel:
        markup.add(types.KeyboardButton("Cancelar"))  # Botão de cancelamento padronizado
    return markup

@bot.message_handler(commands=["start"])
def cmd_start(message):
    user_id = message.chat.id
    logger.debug(f"[NAVEGAÇÃO] Usuário {user_id} iniciou o bot com o comando /start")
    logger.debug(f"[COMPORTAMENTO ESPERADO] Exibir mensagem de boas-vindas e botão 'Iniciar Quiz'")
    
    markup = types.ReplyKeyboardMarkup(row_width=1)
    markup.add(types.KeyboardButton("Iniciar Quiz"))
    util_send_message_with_retry(user_id, "👋 Olá! Bem-vindo ao Quiz de Concursos Públicos.\n\nClique no botão abaixo para começar:", reply_markup=markup)
    
    logger.debug(f"[COMPORTAMENTO REAL] Mensagem de boas-vindas enviada para o usuário {user_id}")
    logger.debug(f"[FUNÇÃO RESPONSÁVEL] start")

def start_quiz(user_id):
    logger.debug(f"[NAVEGAÇÃO] Iniciando quiz para o usuário {user_id}")
    logger.debug(f"[COMPORTAMENTO ESPERADO] Configurar estado do usuário e buscar questões")

    if user_id not in user_quiz_state:
        logger.warning(f"[ERRO] Tentativa de iniciar quiz para usuário {user_id} não registrado")
        logger.debug(f"[COMPORTAMENTO REAL] Quiz não iniciado devido a usuário não registrado")
        logger.debug(f"[FUNÇÃO RESPONSÁVEL] start_quiz")
        return

    try:
        quantidade = user_quiz_state[user_id].get('quantidade', 0)
        concurso = user_quiz_state[user_id].get('concurso')
        prova = user_quiz_state[user_id].get('prova')
        disciplina = user_quiz_state[user_id].get('disciplina')
        fonte = user_quiz_state[user_id].get('fonte')

        logger.debug(f"[ESTADO] Usuário {user_id}: Quantidade={quantidade}, Concurso={concurso}, Prova={prova}, Disciplina={disciplina}, Fonte={fonte}")

        if user_quiz_state[user_id].get("simulado_mode"):
            logger.debug(f"[NAVEGAÇÃO] Iniciando simulado para o usuário {user_id}")
            user_quiz_state[user_id]["questoes_simulado"] = fetch_simulation_questions(user_id, quantidade)            
            logger.debug(f"[COMPORTAMENTO REAL] Questões do simulado obtidas: {len(user_quiz_state[user_id]['questoes_simulado'])}")
            user_quiz_state[user_id]["indice_atual"] = 0
        else:
            logger.debug(f"[NAVEGAÇÃO] Iniciando quiz normal para o usuário {user_id}")
            modo_original = user_quiz_state[user_id].get("modo_quiz", "shuffle_mode")
            logger.debug(f"[ESTADO] Modo de quiz escolhido: {modo_original}")

            if modo_original == "badass_mode":
                user_quiz_state[user_id]["fila_de_questoes"] = fetch_badass_questions(user_id, quantidade, concurso, prova, disciplina, fonte)
            elif modo_original == "shuffle_completo_mode":
                user_quiz_state[user_id]["fila_de_questoes"] = fetch_quiz_questions(user_id, quantidade, shuffle_completo=True)
            elif modo_original == "badass_geral_mode":
                user_quiz_state[user_id]["fila_de_questoes"] = fetch_badass_questions(user_id, quantidade, modo_geral=True)
            else:  # shuffle_mode ou quiz normal
                user_quiz_state[user_id]["fila_de_questoes"] = fetch_quiz_questions(user_id, quantidade, concurso, prova, disciplina, fonte)

            logger.debug(f"[COMPORTAMENTO REAL] Fila de questões obtida: {len(user_quiz_state[user_id]['fila_de_questoes'])}")

            if not user_quiz_state[user_id]["fila_de_questoes"]:
                logger.error(f"[ERRO] Nenhuma questão encontrada para o usuário {user_id} no modo {modo_original}")
                util_send_message_with_retry(user_id, "Desculpe, não foi possível encontrar questões para o quiz. Por favor, tente novamente mais tarde.")
                logger.debug(f"[COMPORTAMENTO REAL] Mensagem de erro enviada ao usuário {user_id}")
                return

        user_quiz_state[user_id]["esperando_resposta"] = False
        logger.debug(f"[ESTADO] 'esperando_resposta' definido como False para o usuário {user_id}")
        send_next_question(user_id)
        logger.debug(f"[NAVEGAÇÃO] Próxima questão enviada para o usuário {user_id}")

    except Exception as e:
        logger.error(f"[ERRO] Erro ao iniciar quiz para o usuário {user_id}: {e}", exc_info=True)
        util_send_message_with_retry(user_id, "Ocorreu um erro ao iniciar o quiz. Por favor, tente novamente.")
        logger.debug(f"[COMPORTAMENTO REAL] Mensagem de erro enviada ao usuário {user_id}")

    logger.debug(f"[FUNÇÃO RESPONSÁVEL] start_quiz")

@bot.message_handler(func=lambda message: message.text == "Iniciar Quiz" or message.text == "Voltar ao Início")
def handle_start_quiz(message):
     user_id = message.chat.id
     logger.debug(f"[NAVEGAÇÃO] Usuário {user_id} clicou em 'Iniciar Quiz' ou 'Voltar ao Início'")
     logger.debug(f"[COMPORTAMENTO ESPERADO] Apresentar opções de modo de quiz")
     logger.debug(f"[FUNÇÃO RESPONSÁVEL] handle_start_quiz")
 
     try:
         user_quiz_state[user_id] = {}
         logger.debug(f"[ESTADO] Estado do usuário {user_id} reinicializado")
 
         user_quiz_state[user_id]['estado_atual'] = 'escolher_modo'
         logger.debug(f"[ESTADO ATUALIZADO] Estado atual do usuário {user_id}: 'escolher_modo'")
 
         markup = types.ReplyKeyboardMarkup(row_width=2)
         markup.add(
             types.KeyboardButton("Concurso Específico"),
             types.KeyboardButton("Random Geral"),
             types.KeyboardButton("Single"),
             types.KeyboardButton("The Most Badass Geral"),
             types.KeyboardButton("Encerrar App")  # Novo botão adicionado aqui
         )
         util_send_message_with_retry(user_id, "Escolha o modo de quiz ou encerre o aplicativo:", reply_markup=markup)
         logger.debug(f"[COMPORTAMENTO REAL] Opções de modo de quiz e encerramento apresentadas para o usuário {user_id}")
         logger.debug(f"[NAVEGAÇÃO POSSÍVEL] Usuário pode escolher um modo de quiz ou encerrar o app")
     except Exception as e:
         logger.error(f"[ERRO] Erro ao apresentar opções para o usuário {user_id}: {e}", exc_info=True)
         util_send_message_with_retry(user_id, "Ocorreu um erro. Por favor, tente novamente.")
         logger.debug(f"[COMPORTAMENTO REAL] Mensagem de erro enviada ao usuário {user_id}")
 
     logger.debug(f"[ESTADO FINAL] Usuário {user_id}: {user_quiz_state.get(user_id)}")

def util_manage_reviews_and_decay():
    """
    Verifica todas as questões no banco de dados, marca as que precisam de revisão
    e atualiza o campo `points_with_decay`.

    Esta função itera por todos os documentos na coleção `userActivityLog` e:
    1. Verifica se a diferença entre a data atual e a data da última sessão é maior 
       que o intervalo de repetição espaçada. Se for, o campo `revisao_necessaria` 
       é atualizado para `True`.
    2. Recalcula o campo `points_with_decay` para cada questão, aplicando o decaimento 
       com base no tempo desde a última sessão.

    A função inclui tratamento de erros para lidar com possíveis exceções durante a 
    iteração dos documentos e a atualização do banco de dados.
    """
    logger.info("Iniciando verificação diária de revisões e atualização de points_with_decay.")
    try:
        for questao_usuario in db["userActivityLog"].find():
            try:
                # Verificar necessidade de revisão
                revisao_necessaria = analyze_review_need(questao_usuario)
                if revisao_necessaria:
                    db["userActivityLog"].update_one(
                        {"_id": questao_usuario["_id"]},
                        {"$set": {"revisao_necessaria": True}}
                    )
                    logger.debug(
                        f"Revisão marcada como necessária para questão {questao_usuario['question_id']} do usuário {questao_usuario['user_id']}"
                    )

                # Atualizar points_with_decay
                data_atual = datetime.now(ZoneInfo("America/Sao_Paulo"))
                points_with_decay = calculate_decay(questao_usuario['points'], questao_usuario["datas"]["ultima_sessao"], data_atual)
                db["userActivityLog"].update_one(
                    {"_id": questao_usuario["_id"]},
                    {"$set": {"points_with_decay": points_with_decay}}
                )
                logger.debug(
                    f"points_with_decay atualizado para {points_with_decay} para questão {questao_usuario['question_id']} do usuário {questao_usuario['user_id']}"
                )

            except (KeyError, TypeError) as e:
                logger.error(
                    f"Erro ao processar questão {questao_usuario.get('question_id', 'ID não disponível')} do usuário {questao_usuario.get('user_id', 'ID não disponível')}: {e}",
                    exc_info=True,
                )
    except PyMongoError as e:
        logger.error(f"Erro do MongoDB durante a verificação de revisões: {e}", exc_info=True)
    logger.info("Verificação diária de revisões e atualização de points_with_decay concluída.")

schedule.every().day.at("00:00").do(util_manage_reviews_and_decay)

@bot.message_handler(func=lambda message: message.text in ["Concurso Específico", "Random Geral", "Single", "The Most Badass Geral", "Voltar", "Encerrar App"])
def handle_select_quiz_mode(message):
    user_id = message.chat.id
    escolha = message.text
    logger.debug(f"[NAVEGAÇÃO] Usuário {user_id} selecionou: {escolha}")
    logger.debug(f"[ESTADO ATUAL] Usuário {user_id}: {user_quiz_state.get(user_id)}")
    logger.debug(f"[FUNÇÃO RESPONSÁVEL] handle_select_quiz_mode")

    try:
        if escolha == "Encerrar App":
            logger.debug(f"[NAVEGAÇÃO] Usuário {user_id} escolheu 'Encerrar App'")
            handle_app_exit(message)
            return
        if escolha == "Voltar":
            logger.debug(f"[NAVEGAÇÃO] Usuário {user_id} clicou em 'Voltar'")
            estado_atual = user_quiz_state.get(user_id, {}).get('estado_atual')
            logger.debug(f"[ESTADO ATUAL] Usuário {user_id}: {estado_atual}")

            if estado_atual == 'escolher_prova_ou_modo':
                logger.debug(f"[COMPORTAMENTO ESPERADO] Voltar para a escolha de concurso")
                user_quiz_state[user_id]['estado_atual'] = 'handle_select_contest'
                display_contest_options(user_id)
            elif estado_atual == 'handle_select_contest':
                logger.debug(f"[COMPORTAMENTO ESPERADO] Voltar para a escolha do modo de quiz")
                user_quiz_state[user_id]['estado_atual'] = 'escolher_modo'
                handle_start_quiz(message)
            elif estado_atual == 'escolhendo_disciplina':
                logger.debug(f"[COMPORTAMENTO ESPERADO] Voltar para a escolha de prova")
                handle_select_subject_or_mode(message, estado_atual='voltando_de_disciplinas')
            elif estado_atual == 'escolher_fonte':
                logger.debug(f"[COMPORTAMENTO ESPERADO] Voltar para a escolha de disciplina")
                user_quiz_state[user_id]['estado_atual'] = 'escolher_disciplina'
                display_subject_options(user_id, user_quiz_state[user_id]['concurso'], user_quiz_state[user_id]['prova'])
            # Separando a lógica do "Voltar" para 'escolher_quantidade'
            elif estado_atual == 'escolher_quantidade':  
                modo_quiz = user_quiz_state[user_id].get("modo_quiz")
                if modo_quiz == "Concurso Específico":
                    logger.debug(f"[COMPORTAMENTO ESPERADO] Voltar para a escolha de fonte (Concurso Específico)")
                    user_quiz_state[user_id]['estado_atual'] = 'escolher_fonte'
                    prompt_source_selection(user_id)
                else:  # Random Geral, Single, The Most Badass Geral
                    logger.debug(f"[COMPORTAMENTO ESPERADO] Voltar para a escolha de modo de quiz")
                    user_quiz_state[user_id]['estado_atual'] = 'escolher_modo'
                    handle_start_quiz(message)
            # Mantendo a lógica para 'fetch_questions'
            elif estado_atual == 'fetch_questions':  
                logger.debug(f"[COMPORTAMENTO ESPERADO] Voltar para a escolha de modo de quiz (Single Mode)")
                user_quiz_state[user_id]['estado_atual'] = 'escolher_modo'
                handle_start_quiz(message)
            else:
                logger.warning(f"[EXCEÇÃO] Estado desconhecido para o usuário {user_id}: {estado_atual}")
                handle_start_quiz(message)
            return

        if user_id not in user_quiz_state:
            user_quiz_state[user_id] = {}

        if escolha == "Concurso Específico":
            logger.debug(f"[NAVEGAÇÃO] Usuário {user_id} escolheu 'Concurso Específico'")
            user_quiz_state[user_id]['estado_atual'] = 'handle_select_contest'
            display_contest_options(user_id)
        elif escolha in ["Random Geral", "Single", "The Most Badass Geral"]:
            logger.debug(f"[NAVEGAÇÃO] Usuário {user_id} escolheu '{escolha}'")
            user_quiz_state[user_id] = {
                "modo_quiz": escolha,
                "shuffle_completo_mode": escolha == "Random Geral",
                "single_mode": escolha == "Single",
                "badass_geral_mode": escolha == "The Most Badass Geral",
                "respondidas": 0,
                "acertos": 0,
                "quantidade": 1 if escolha == "Single" else 0,
                "fila_de_questoes": [],
                "concurso": None,
                "estado_atual": 'escolher_quantidade' if escolha != "Single" else 'fetch_questions'
            }
            logger.debug(f"[ESTADO ATUALIZADO] Usuário {user_id}: {user_quiz_state[user_id]}")

            if escolha in ["Random Geral", "The Most Badass Geral"]:
                logger.debug(f"[COMPORTAMENTO ESPERADO] Pedir quantidade de questões")
                prompt_question_quantity(user_id)
            else:  # Single mode
                logger.debug(f"[COMPORTAMENTO ESPERADO] Apresentar opções de busca para Single mode")
                markup = util_create_markup(["Buscar por ID", "Buscar por Palavras-Chave", "Voltar"])
                util_send_message_with_retry(user_id, "Como você deseja buscar a questão?", reply_markup=markup)

        logger.debug(f"[ESTADO FINAL] Usuário {user_id}: {user_quiz_state.get(user_id)}")

    except Exception as e:
        logger.error(f"[ERRO] Erro ao processar escolha de modo de quiz para o usuário {user_id}: {e}", exc_info=True)
        util_send_message_with_retry(user_id, "Ocorreu um erro. Por favor, tente novamente.")
        logger.debug(f"[COMPORTAMENTO REAL] Mensagem de erro enviada ao usuário {user_id}")

def handle_app_exit(message):
    user_id = message.chat.id
    logger.debug(f"[NAVEGAÇÃO] Usuário {user_id} selecionou: Encerrar App")
    logger.debug(f"[FUNÇÃO RESPONSÁVEL] handle_app_exit")

    try:
        # Remover o teclado personalizado
        markup = types.ReplyKeyboardRemove(selective=False)
        
        # Enviar mensagem de despedida
        mensagem_despedida = (
            "🙋‍♂️ Obrigado por usar nosso Quiz de Concursos Públicos!\n\n"
            "Esperamos que tenha sido útil para seus estudos. 📚✨\n\n"
            "Lembre-se:\n"
            "🔹 A consistência é a chave do sucesso.\n"
            "🔹 Cada questão que você pratica é um passo em direção ao seu objetivo.\n\n"
            "Volte sempre que quiser treinar mais! Use /start para iniciar uma nova sessão.\n\n"
            "Boa sorte em sua jornada de estudos! 🍀🎓"
        )
        
        util_send_message_with_retry(user_id, mensagem_despedida, reply_markup=markup)
        logger.debug(f"[COMPORTAMENTO REAL] Mensagem de despedida enviada para o usuário {user_id}")

        # Remover dados do usuário do estado (se existirem)
        user_quiz_state.pop(user_id, None)
        logger.debug(f"[ESTADO] Dados do usuário {user_id} removidos (se existiam)")

    except Exception as e:
        logger.error(f"[ERRO] Erro ao encerrar o app para o usuário {user_id}: {e}", exc_info=True)
        util_send_message_with_retry(user_id, "Ocorreu um erro ao encerrar o aplicativo. Por favor, tente /start para reiniciar.")
        logger.debug(f"[COMPORTAMENTO REAL] Mensagem de erro enviada ao usuário {user_id}")

def display_contest_options(user_id):
    logger.debug(f"[NAVEGAÇÃO] Usuário {user_id} entrou na seleção de concurso")
    logger.debug(f"[COMPORTAMENTO ESPERADO] Exibir opções de concurso (CPNU, BNDES, Receita Federal) e botão 'Voltar'")
    logger.debug(f"[FUNÇÃO RESPONSÁVEL] display_contest_options")

    markup = types.ReplyKeyboardMarkup(row_width=2)
    markup.add(
        *[types.KeyboardButton(exam.value) for exam in EXAMS_LIST],
        types.KeyboardButton("Voltar")
    )
    util_send_message_with_retry(user_id, "Escolha o concurso:", reply_markup=markup)
    logger.debug(f"[COMPORTAMENTO REAL] Opções de concurso apresentadas para o usuário {user_id}")
    logger.debug(f"[NAVEGAÇÃO POSSÍVEL] Usuário pode escolher um concurso ou clicar em 'Voltar'")

@bot.message_handler(func=lambda message: message.text in [exam.value for exam in EXAMS_LIST] + ["Voltar"])
def handle_select_contest(message):
    user_id = message.chat.id
    escolha = message.text
    logger.debug(f"[NAVEGAÇÃO] Usuário {user_id} selecionou: {escolha}")
    logger.debug(f"[ESTADO ATUAL] Usuário {user_id}: {user_quiz_state.get(user_id)}")
    logger.debug(f"[FUNÇÃO RESPONSÁVEL] handle_select_contest")

    try:
        if escolha == "Voltar":
            logger.debug(f"[NAVEGAÇÃO] Usuário {user_id} clicou em 'Voltar' na escolha do concurso")
            logger.debug(f"[COMPORTAMENTO ESPERADO] Voltar para a escolha do modo de quiz")
            user_quiz_state[user_id]['estado_atual'] = 'escolher_modo'
            handle_start_quiz(message)
            logger.debug(f"[COMPORTAMENTO REAL] Usuário {user_id} redirecionado para a escolha do modo de quiz")
            return

        if escolha in [exam.value for exam in EXAMS_LIST]:
            logger.debug(f"[NAVEGAÇÃO] Usuário {user_id} escolheu o concurso: {escolha}")
            user_quiz_state[user_id].update({
                "concurso": escolha,
                "prova": None,
                "disciplina": None,
                "modo_quiz": "Concurso Específico",
                "shuffle_mode": False,
                "simulado_mode": False,
                "badass_mode": False,
                "estado_atual": 'escolher_prova_ou_modo'
            })
            logger.debug(f"[ESTADO ATUALIZADO] Usuário {user_id}: {user_quiz_state[user_id]}")
        else:
            logger.warning(f"[EXCEÇÃO] Usuário {user_id} fez uma escolha inválida: {escolha}")
            escolha = user_quiz_state[user_id].get("concurso")
            logger.debug(f"[COMPORTAMENTO REAL] Usando o concurso armazenado no estado: {escolha}")

        logger.debug(f"[NAVEGAÇÃO] Usuário {user_id} prosseguindo com o concurso: {escolha}")

        if escolha == EXAMS_LIST.CPNU.value:
            provas_disciplinas_concurso = EXAMS_SUBJECTS_CPNU
        elif escolha == EXAMS_LIST.BNDES.value:
            provas_disciplinas_concurso = EXAMS_SUBJECTS_BNDES
        elif escolha == EXAMS_LIST.RFB.value:
            provas_disciplinas_concurso = EXAMS_SUBJECTS_RFB
        else:
            logger.error(f"[ERRO] Concurso não reconhecido: {escolha}")
            util_send_message_with_retry(user_id, "Concurso não reconhecido. Por favor, tente novamente.")
            return

        markup = types.ReplyKeyboardMarkup(row_width=2)
        markup.add(*[types.KeyboardButton(prova) for prova in provas_disciplinas_concurso.keys()])
        markup.add(
            types.KeyboardButton("Shuffle"),
            types.KeyboardButton("Simulado"),
            types.KeyboardButton("Most Badass"),
            types.KeyboardButton("Voltar")
        )

        util_send_message_with_retry(user_id, "Escolha a Prova, clique em Shuffle para iniciar o quiz aleatório, ou selecione Simulado para um teste completo, ou 'Most Badass' para questões desafiadoras:", reply_markup=markup)
        logger.debug(f"[COMPORTAMENTO REAL] Opções de prova e modos apresentados para o usuário {user_id}")
        logger.debug(f"[NAVEGAÇÃO POSSÍVEL] Usuário pode escolher uma prova, um modo especial ou clicar em 'Voltar'")

    except Exception as e:
        logger.error(f"[ERRO] Erro ao processar escolha de concurso para o usuário {user_id}: {e}", exc_info=True)
        util_send_message_with_retry(user_id, "Ocorreu um erro. Por favor, tente novamente.")
        logger.debug(f"[COMPORTAMENTO REAL] Mensagem de erro enviada ao usuário {user_id}")

    logger.debug(f"[ESTADO FINAL] Usuário {user_id}: {user_quiz_state.get(user_id)}")

def display_subject_options(user_id, concurso, prova):
    logger.debug(f"[INÍCIO] display_subject_options para usuário {user_id}")
    logger.debug(f"[PARÂMETROS] Concurso: {concurso}, Prova: {prova}")
    logger.debug(f"[ESTADO INICIAL] user_quiz_state[{user_id}]: {user_quiz_state.get(user_id, 'Não existe')}")

    if concurso == "CPNU":
        provas_disciplinas = EXAMS_SUBJECTS_CPNU
    elif concurso == "BNDES":
        provas_disciplinas = EXAMS_SUBJECTS_BNDES
    elif concurso == "RFB":
        provas_disciplinas = EXAMS_SUBJECTS_RFB
    else:
        logger.error(f"[ERRO] Concurso não reconhecido: {concurso}")
        util_send_message_with_retry(user_id, "Erro ao identificar o concurso. Por favor, reinicie o quiz.")
        return

    logger.debug(f"[SELEÇÃO] Usando provas_disciplinas para {concurso}")

    subjects = provas_disciplinas.get(prova, [])
    logger.debug(f"[SUBJECTS] Obtidas para prova '{prova}': {subjects}")

    subjects = list(dict.fromkeys(d for d in subjects if d != "Voltar"))
    subjects.append("Voltar")
    logger.debug(f"[SUBJECTS PROCESSADAS] Lista final: {subjects}")

    markup = util_create_markup(subjects)
    logger.debug(f"[MARKUP] Criado para {len(subjects)} subjects")

    util_send_message_with_retry(user_id, "Escolha a disciplina:", reply_markup=markup)
    logger.debug(f"[MENSAGEM] Enviada solicitação de escolha de disciplina para usuário {user_id}")

    user_quiz_state[user_id].update({
        "estado_atual": 'escolhendo_disciplina',
        "disciplinas_disponiveis": subjects,
        "concurso": concurso,
        "prova": prova
    })
    logger.debug(f"[ESTADO ATUALIZADO] Novo estado para usuário {user_id}: {user_quiz_state[user_id]}")

    if len(subjects) <= 1:  # Apenas "Voltar"
        logger.warning(f"[EXCEÇÃO] Nenhuma disciplina disponível para usuário {user_id}. Concurso: {concurso}, Prova: {prova}")
        util_send_message_with_retry(user_id, "Desculpe, não há disciplinas disponíveis para esta prova. Por favor, escolha outra prova.")
        logger.debug(f"[REDIRECIONAMENTO] Voltando para escolha de prova para usuário {user_id}")
        user_quiz_state[user_id]['estado_atual'] = 'escolher_prova_ou_modo'
        display_exam_options(user_id, concurso)  # Certifique-se de que esta função está definida
    else:
        logger.debug(f"[OPÇÕES] Usuário {user_id} pode selecionar entre {len(subjects)} disciplinas ou 'Voltar'")

    logger.debug(f"[COMPORTAMENTO ESPERADO AO VOLTAR] Retornar para a seleção de prova do concurso {concurso}")
    logger.debug(f"[FIM] display_subject_options para usuário {user_id}")

@bot.message_handler(func=lambda message: message.text in exams + ["Shuffle", "Simulado", "Most Badass", "Single", "Voltar"])
def handle_select_subject_or_mode(message, estado_atual=None):
    user_id = message.chat.id
    escolha = message.text
    logger.debug(f"[NAVEGAÇÃO] Usuário {user_id} selecionou: {escolha}")
    logger.debug(f"[ESTADO ATUAL] Usuário {user_id}: {user_quiz_state.get(user_id)}")
    logger.debug(f"[FUNÇÃO RESPONSÁVEL] handle_select_subject_or_mode")

    try:
        if escolha == "Voltar":
            logger.debug(f"[NAVEGAÇÃO] Usuário {user_id} clicou em 'Voltar'")

            if estado_atual is not None:
                user_quiz_state[user_id]['estado_atual'] = estado_atual
                logger.debug(f"[ESTADO ATUALIZADO] Novo estado_atual: {estado_atual}")
            else:
                estado_atual = user_quiz_state[user_id].get('estado_atual')
                logger.debug(f"[ESTADO ATUAL] Estado_atual do user_quiz_state: {estado_atual}")

            logger.debug(f"[ESTADO ATUAL] Usuário {user_id}: {estado_atual}")

            if estado_atual == 'escolher_prova_ou_modo':
                logger.debug(f"[COMPORTAMENTO ESPERADO] Voltar para a seleção de concurso")
                user_quiz_state[user_id]['estado_atual'] = 'handle_select_contest'
                display_contest_options(user_id)
                logger.debug(f"[COMPORTAMENTO REAL] Usuário {user_id} redirecionado para a escolha de concurso")
            elif estado_atual == 'escolhendo_disciplina':
                logger.debug(f"[COMPORTAMENTO ESPERADO] Voltar para a seleção de prova")
                handle_select_subject_or_mode(message, estado_atual='voltando_de_disciplinas')
            elif estado_atual == 'voltando_de_disciplinas':
                logger.debug(f"[COMPORTAMENTO ESPERADO] Exibir opções de prova")
                user_quiz_state[user_id]['estado_atual'] = 'escolher_prova_ou_modo'
                concurso = user_quiz_state[user_id].get("concurso")
                logger.debug(f"[ESTADO] Concurso atual do usuário {user_id}: {concurso}")
                
                if concurso == "CPNU":
                    provas_disciplinas_concurso = EXAMS_SUBJECTS_CPNU
                elif concurso == "BNDES":
                    provas_disciplinas_concurso = EXAMS_SUBJECTS_BNDES
                elif concurso == "RFB":
                    provas_disciplinas_concurso = EXAMS_SUBJECTS_RFB
                else:
                    logger.error(f"[ERRO] Concurso não reconhecido: {concurso}")
                    util_send_message_with_retry(user_id, "Erro ao identificar o concurso. Por favor, reinicie o quiz.")
                    return

                markup = types.ReplyKeyboardMarkup(row_width=2)
                markup.add(*[types.KeyboardButton(prova) for prova in provas_disciplinas_concurso.keys()])
                markup.add(
                    types.KeyboardButton("Shuffle"),
                    types.KeyboardButton("Simulado"),
                    types.KeyboardButton("Most Badass"),
                    types.KeyboardButton("Voltar")
                )

                util_send_message_with_retry(user_id, "Escolha a Prova, clique em Shuffle para iniciar o quiz aleatório, ou selecione Simulado para um teste completo, ou 'Most Badass' para questões desafiadoras:", reply_markup=markup)
                logger.debug(f"[COMPORTAMENTO REAL] Menu de seleção de prova apresentado para o usuário {user_id}")
            return

        if escolha in ["Shuffle", "Simulado", "Most Badass", "Single"]:
            logger.debug(f"[NAVEGAÇÃO] Usuário {user_id} selecionou modo especial: {escolha}")
            user_quiz_state[user_id].update({
                "modo_quiz": escolha,
                "shuffle_mode": escolha == "Shuffle",
                "simulado_mode": escolha == "Simulado",
                "badass_mode": escolha == "Most Badass",
                "single_mode": escolha == "Single",
                "respondidas": 0,
                "acertos": 0,
                "quantidade": 1 if escolha == "Single" else 0,
                "estado_atual": 'escolher_quantidade' if escolha != "Single" else 'fetch_questions'
            })
            logger.debug(f"[ESTADO ATUALIZADO] Usuário {user_id}: {user_quiz_state[user_id]}")

            if escolha in ["Shuffle", "Simulado", "Most Badass"]:
                logger.debug(f"[COMPORTAMENTO ESPERADO] Pedir quantidade de questões")
                prompt_question_quantity(user_id)
            else:
                logger.debug(f"[COMPORTAMENTO ESPERADO] Apresentar opções de busca para Single mode")
                markup = util_create_markup(["Buscar por ID", "Buscar por Palavras-Chave", "Voltar"])
                util_send_message_with_retry(user_id, "Como você deseja buscar a questão?", reply_markup=markup)

        elif escolha in exams:
            logger.debug(f"[NAVEGAÇÃO] Usuário {user_id} selecionou a prova: {escolha}")
            user_quiz_state[user_id].update({
                "prova": escolha,
                "respondidas": 0,
                "acertos": 0,
                "estado_atual": 'escolher_disciplina'
            })
            logger.debug(f"[ESTADO ATUALIZADO] Usuário {user_id}: {user_quiz_state[user_id]}")

            concurso = user_quiz_state[user_id].get("concurso")
            if concurso == "CPNU":
                disciplinas_prova = EXAMS_SUBJECTS_CPNU.get(escolha, [])
            elif concurso == "BNDES":
                disciplinas_prova = EXAMS_SUBJECTS_BNDES.get(escolha, [])
            elif concurso == "RFB":
                disciplinas_prova = EXAMS_SUBJECTS_RFB.get(escolha, [])
            else:
                logger.error(f"[ERRO] Concurso não reconhecido: {concurso}")
                util_send_message_with_retry(user_id, "Erro ao identificar o concurso. Por favor, reinicie o quiz.")
                return

            if disciplinas_prova:
                logger.debug(f"[COMPORTAMENTO ESPERADO] Apresentar opções de disciplina")
                display_subject_options(user_id, concurso, escolha)
            else:
                logger.error(f"[EXCEÇÃO] Nenhuma disciplina encontrada para a prova {escolha} do concurso {concurso}")
                util_send_message_with_retry(user_id, "Ocorreu um erro ao selecionar a prova. Por favor, tente novamente.")
                logger.debug(f"[COMPORTAMENTO REAL] Mensagem de erro enviada ao usuário {user_id}")

    except Exception as e:
        logger.error(f"[ERRO] Erro ao processar a escolha para o usuário {user_id}: {e}", exc_info=True)
        util_send_message_with_retry(user_id, "Desculpe, ocorreu um erro. Por favor, tente novamente.")
        logger.debug(f"[COMPORTAMENTO REAL] Mensagem de erro enviada ao usuário {user_id}")

    logger.debug(f"[ESTADO FINAL] Usuário {user_id}: {user_quiz_state.get(user_id)}")

@bot.message_handler(func=lambda message: any(disciplina in message.text for disciplina in all_subjects) or message.text == "Voltar")
def handle_select_subject(message):
    """
    Lida com a escolha da disciplina pelo usuário,
    armazenando a escolha e avançando para a próxima etapa.
    """
    user_id = message.chat.id
    disciplina = message.text
    logger.debug(f"[NAVEGAÇÃO] Usuário {user_id} selecionou disciplina: {disciplina}")
    logger.debug(f"[ESTADO ATUAL] Usuário {user_id}: {user_quiz_state.get(user_id)}")
    logger.debug(f"[FUNÇÃO RESPONSÁVEL] handle_select_subject")

    try:
        if disciplina == "Voltar":
            logger.debug(f"[NAVEGAÇÃO] Usuário {user_id} clicou em 'Voltar' na seleção de disciplina")
            logger.debug(f"[COMPORTAMENTO ESPERADO] Voltar para a seleção de prova do concurso")

            # Correção: chamar a função com o estado 'voltando_de_disciplinas'
            handle_select_subject_or_mode(message, estado_atual='voltando_de_disciplinas')
            return  # Adicione um return aqui para evitar a execução do código abaixo

        user_quiz_state[user_id]["disciplina"] = disciplina
        user_quiz_state[user_id]["estado_atual"] = "escolher_fonte"
        logger.debug(f"[ESTADO ATUALIZADO] Usuário {user_id}: {user_quiz_state[user_id]}")

        logger.debug(f"[COMPORTAMENTO ESPERADO] Avançar para a seleção de fonte")
        prompt_source_selection(user_id)

    except Exception as e:
        logger.error(f"[ERRO] Erro ao processar escolha de disciplina para o usuário {user_id}: {e}", exc_info=True)
        util_send_message_with_retry(user_id, "Ocorreu um erro ao selecionar a disciplina. Por favor, tente novamente.")
        logger.debug(f"[COMPORTAMENTO REAL] Mensagem de erro enviada ao usuário {user_id}")

def prompt_source_selection(user_id):
    logger.debug(f"[NAVEGAÇÃO] Usuário {user_id} entrou na seleção de fonte")
    logger.debug(f"[ESTADO ATUAL] Usuário {user_id}: {user_quiz_state.get(user_id)}")
    logger.debug(f"[FUNÇÃO RESPONSÁVEL] prompt_source_selection")

    try:
        concurso = user_quiz_state[user_id].get("concurso")
        logger.debug(f"Concurso obtido do estado do usuário: {concurso}")

        if not concurso:
            logger.error(f"[EXCEÇÃO] Concurso não encontrado para o usuário {user_id}")
            util_send_message_with_retry(user_id, "Erro: Concurso não encontrado. Por favor, reinicie o quiz.")
            handle_start_quiz(user_id)
            return

        opcoes_fonte = {
            EXAMS_LIST.CPNU.value: ["Resumo", "Legislação", "Jurisprudência", "Cesgranrio", "CESPE", "ESAF", "Q Concursos", "Estratégia", "Gran Concursos", "ChatGPT", "Gemini", "Outras", "Todas"],
            EXAMS_LIST.BNDES.value: ["Resumo", "Legislação", "Jurisprudência", "Cesgranrio", "CESPE", "ESAF", "Q Concursos", "Estratégia", "Gran Concursos", "ChatGPT", "Gemini", "Outras", "Todas"],
            EXAMS_LIST.RFB.value: ["Resumo", "Legislação", "Jurisprudência", "Cesgranrio", "CESPE", "ESAF", "Q Concursos", "Estratégia", "Gran Concursos", "ChatGPT", "Gemini", "Outras", "Todas"],
            EXAMS_LIST.GERAL.value: ["Resumo", "Legislação", "Jurisprudência", "Cesgranrio", "CESPE", "ESAF", "Q Concursos", "Estratégia", "Gran Concursos", "ChatGPT", "Gemini", "Outras", "Todas"]
        }

        if concurso not in opcoes_fonte:
            logger.error(f"[EXCEÇÃO] Concurso {concurso} não reconhecido para o usuário {user_id}")
            util_send_message_with_retry(user_id, "Erro: Concurso não reconhecido. Por favor, reinicie o quiz.")
            handle_start_quiz(user_id)
            return

        fontes = opcoes_fonte[concurso]
        fontes.append("Voltar")
        markup = util_create_markup(fontes)
        util_send_message_with_retry(user_id, "Escolha a Fonte das Questões:", reply_markup=markup)
        user_quiz_state[user_id]["estado_atual"] = "escolher_fonte"
        user_quiz_state[user_id]["opcoes_fonte"] = fontes
        logger.debug(f"[ESTADO ATUALIZADO] Usuário {user_id}: {user_quiz_state[user_id]}")
        logger.debug(f"[NAVEGAÇÃO POSSÍVEL] Usuário {user_id} pode escolher uma fonte ou clicar em 'Voltar'")

    except Exception as e:
        logger.error(f"[ERRO] Erro ao perguntar a fonte para o usuário {user_id}: {e}", exc_info=True)
        util_send_message_with_retry(user_id, "Erro: Informações do quiz não encontradas. Por favor, reinicie o quiz.")
        handle_start_quiz(user_id)

@bot.message_handler(func=lambda message: message.text in ["Resumo", "Legislação", "Jurisprudência", "Cesgranrio", "CESPE", "ESAF", "Q Concursos", "Estratégia", "Gran Concursos", "ChatGPT", "Gemini", "Outras", "Todas", "Voltar"])
def handle_select_source(message):
    """
    Processa a escolha da fonte de questões pelo usuário.
    """
    user_id = message.chat.id
    fonte_escolhida = message.text
    logger.debug(f"[NAVEGAÇÃO] Usuário {user_id} selecionou fonte: {fonte_escolhida}")
    logger.debug(f"[ESTADO ATUAL] Usuário {user_id}: {user_quiz_state.get(user_id)}")
    logger.debug(f"[FUNÇÃO RESPONSÁVEL] handle_select_source")

    try:
        if fonte_escolhida == "Voltar":
            logger.debug(f"[NAVEGAÇÃO] Usuário {user_id} clicou em 'Voltar' na seleção de fonte")
            logger.debug(f"[COMPORTAMENTO ESPERADO] Voltar para a seleção de disciplina")
            concurso = user_quiz_state[user_id].get("concurso")
            prova = user_quiz_state[user_id].get("prova")
            if concurso and prova:
                display_subject_options(user_id, concurso, prova)
                logger.debug(f"[COMPORTAMENTO REAL] Usuário {user_id} redirecionado para a escolha de disciplina")
            else:
                logger.error(f"[ERRO] Concurso ou prova não encontrados para o usuário {user_id}")
                util_send_message_with_retry(user_id, "Erro: Informações do quiz não encontradas. Por favor, reinicie o quiz.")
                handle_start_quiz(message)
            return

        user_quiz_state[user_id]["fonte"] = fonte_escolhida
        user_quiz_state[user_id]["estado_atual"] = "escolher_quantidade"
        logger.debug(f"[ESTADO ATUALIZADO] Usuário {user_id}: {user_quiz_state[user_id]}")
        logger.debug(f"[COMPORTAMENTO ESPERADO] Pedir quantidade de questões")
        prompt_question_quantity(user_id)

    except KeyError as e:
        logger.error(f"[ERRO] Erro ao processar a fonte escolhida para o usuário {user_id}: {e}", exc_info=True)
        util_send_message_with_retry(user_id, "Erro: Informações do quiz não encontradas. Por favor, reinicie o quiz.")
        logger.debug(f"[COMPORTAMENTO REAL] Mensagem de erro enviada ao usuário {user_id}")
        handle_start_quiz(message)


def prompt_question_quantity(user_id, message_text="Quantas questões você deseja responder?"):
    logger.debug(f"[NAVEGAÇÃO] Usuário {user_id} entrou na seleção de quantidade de questões")
    logger.debug(f"[ESTADO ATUAL] Usuário {user_id}: {user_quiz_state.get(user_id)}")
    logger.debug(f"[FUNÇÃO RESPONSÁVEL] prompt_question_quantity")

    try:
        markup = types.ReplyKeyboardMarkup(row_width=2)
        for qtd in QUESTIONS_NUMBER:
            markup.add(types.KeyboardButton(qtd))
        markup.add(types.KeyboardButton("Voltar"))
        util_send_message_with_retry(user_id, message_text, reply_markup=markup)
        user_quiz_state[user_id]["estado_atual"] = "escolher_quantidade"
        user_quiz_state[user_id]["opcoes_quantidade"] = QUESTIONS_NUMBER + ["Voltar"]
        logger.debug(f"[ESTADO ATUALIZADO] Usuário {user_id}: {user_quiz_state[user_id]}")
        logger.debug(f"[NAVEGAÇÃO POSSÍVEL] Usuário pode escolher uma quantidade ou clicar em 'Voltar'")
        logger.debug(f"[COMPORTAMENTO ESPERADO AO VOLTAR] Retornar para a seleção de fonte")
    except Exception as e:
        logger.error(f"[ERRO] Erro ao pedir quantidade de questões para o usuário {user_id}: {e}", exc_info=True)
        util_send_message_with_retry(user_id, "Ocorreu um erro. Por favor, tente novamente.")
        prompt_source_selection(user_id)

@bot.message_handler(func=lambda message: message.text in QUESTIONS_NUMBER or message.text == "Voltar")
def handle_select_question_quantity(message):
    """
    Manipula a escolha da quantidade de questões pelo usuário e inicializa o quiz.
    """
    user_id = message.from_user.id
    logger.debug(f"[NAVEGAÇÃO] Usuário {user_id} selecionou: {message.text}")
    logger.debug(f"[ESTADO ATUAL] Usuário {user_id}: {user_quiz_state.get(user_id)}")
    logger.debug(f"[FUNÇÃO RESPONSÁVEL] handle_select_question_quantity")

    try:
        if message.text == "Voltar":
            logger.debug(f"[NAVEGAÇÃO] Usuário {user_id} clicou em 'Voltar' na escolha da quantidade")
            modo_quiz = user_quiz_state[user_id].get("modo_quiz")

            if modo_quiz in ["Random Geral", "The Most Badass Geral"]:
                logger.debug(f"[COMPORTAMENTO ESPERADO] Voltar para a seleção de modo de quiz")
                handle_start_quiz(message)
            elif modo_quiz == "Concurso Específico":
                logger.debug(f"[COMPORTAMENTO ESPERADO] Voltar para a seleção de fonte")
                user_quiz_state[user_id]["estado_atual"] = "escolher_fonte"
                prompt_source_selection(user_id)
            else:
                logger.warning(f"[EXCEÇÃO] Modo de quiz desconhecido para o usuário {user_id}: {modo_quiz}")
                logger.debug(f"[COMPORTAMENTO ESPERADO] Voltar para o início do quiz")
                handle_start_quiz(message)
            logger.debug(f"[COMPORTAMENTO REAL] Usuário {user_id} redirecionado conforme o modo de quiz")
            return

        quantidade = int(message.text.split()[0])
        logger.debug(f"[DADOS] Usuário {user_id} escolheu a quantidade: {quantidade}")

        uuidNew = str(uuid.uuid4())
        session_uuid = hashlib.md5(uuidNew.encode()).hexdigest()[:8]

        if user_id not in user_quiz_state:
            user_quiz_state[user_id] = {}

        user_quiz_state[user_id].update({
            "quantidade": quantidade,
            "respondidas": 0,
            "acertos": 0,
            "questoes_reportadas": 0,
            "quiz_finalizado": False,
            "session_uuid": session_uuid,
            "questoes_enviadas": 0
        })

        # Adicionar a chave "concurso" aqui
        modo_quiz = user_quiz_state[user_id].get("modo_quiz")
        if modo_quiz in ["Random Geral", "The Most Badass Geral"]:
            user_quiz_state[user_id]["concurso"] = None

        logger.debug(f"[ESTADO ATUALIZADO] Usuário {user_id}: {user_quiz_state[user_id]}")

        logger.debug(f"[COMPORTAMENTO ESPERADO] Iniciar o quiz")
        start_quiz(user_id)

    except Exception as e:
        logger.error(f"[ERRO] Erro ao processar quantidade de questões escolhida pelo usuário {user_id}: {e}", exc_info=True)
        util_send_message_with_retry(user_id, "Ocorreu um erro ao processar sua escolha. Por favor, tente novamente.")
        logger.debug(f"[COMPORTAMENTO REAL] Mensagem de erro enviada ao usuário {user_id}")

    logger.debug(f"[ESTADO FINAL] Usuário {user_id}: {user_quiz_state.get(user_id)}")

def util_validate_object_id(object_id_str):
    try:
        ObjectId(object_id_str)
        return True
    except InvalidId:
        return False

@bot.message_handler(func=lambda message: user_quiz_state.get(message.chat.id, {}).get("single_mode") and message.text in ["Buscar por ID", "Buscar por Palavras-Chave"])
def handle_single_mode_search_selection(message):
    user_id = message.chat.id

    if message.text == "Buscar por ID":
        util_send_message_with_retry(user_id, "Digite o ID da questão (ex:  66afca336caf9b5d37185cdd  ):")
        user_quiz_state[user_id]["single_mode_search_method"] = "id"

    elif message.text == "Buscar por Palavras-Chave":
        util_send_message_with_retry(user_id, "Digite as palavras-chave separadas por espaço:")
        user_quiz_state[user_id]["single_mode_search_method"] = "palavras-chave"

@bot.message_handler(func=lambda message: user_quiz_state.get(message.chat.id, {}).get("single_mode") and "single_mode_search_method" in user_quiz_state[message.chat.id])
def handle_single_mode_search_query(message):
    user_id = message.chat.id
    search_method = user_quiz_state[user_id]["single_mode_search_method"]

    if search_method == "id":
        question_id = message.text
        if util_validate_object_id(question_id):
            try:
                questao = db["todasQuestoes"].find_one({"_id": ObjectId(question_id)})
                if questao:
                    user_quiz_state[user_id]["fila_de_questoes"] = [questao]
                    user_quiz_state[user_id]["quantidade"] = 1  # Definir quantidade para 1 no modo Single

                    # --- Gerar session_uuid ---
                    uuidNew = str(uuid.uuid4())
                    session_uuid = hashlib.md5(uuidNew.encode()).hexdigest()[:8]
                    user_quiz_state[user_id]["session_uuid"] = session_uuid

                    # --- Definir concurso ---
                    user_quiz_state[user_id]["concurso"] = "Geral"

                    send_next_question(user_id)

                    # Resetar as flags do modo Single após enviar a questão
                    user_quiz_state[user_id]["single_mode"] = False
                    del user_quiz_state[user_id]["single_mode_search_method"]
                else:
                    util_send_message_with_retry(user_id, f"Questão com ID {question_id} não encontrada.")

            except Exception as e:
                logger.error(f"Erro ao processar o ID da questão no modo Single para o usuário {user_id}: {e}")
        else:
            util_send_message_with_retry(user_id, "ID inválido. Por favor, digite um ID válido.")

    elif search_method == "palavras-chave":
        keywords = message.text.split()
        questoes = list(db["todasQuestoes"].find({"$text": {"$search": " ".join(keywords)}}))
        if questoes:
            questao = random.choice(questoes)
            user_quiz_state[user_id]["fila_de_questoes"] = [questao]
            user_quiz_state[user_id]["quantidade"] = 1  # Definir quantidade para 1 no modo Single

            # --- Gerar session_uuid ---
            uuidNew = str(uuid.uuid4())
            session_uuid = hashlib.md5(uuidNew.encode()).hexdigest()[:8]
            user_quiz_state[user_id]["session_uuid"] = session_uuid

            # --- Definir concurso ---
            user_quiz_state[user_id]["concurso"] = "Geral"

            send_next_question(user_id)

            # Resetar as flags do modo Single após enviar a questão
            user_quiz_state[user_id]["single_mode"] = False
            del user_quiz_state[user_id]["single_mode_search_method"]
        else:
            util_send_message_with_retry(user_id, f"Nenhuma questão encontrada com as palavras-chave: {', '.join(keywords)}")

def util_update_user_info(user_id, username, data_atual):
    """
    Atualiza as informações do usuário no banco de dados.

    Esta função verifica se o usuário já existe na coleção "usuarios" do banco de dados. 
    Se o usuário não existir, ele será inserido com as informações fornecidas. 
    Caso o usuário já exista, o campo "ultimo_acesso" será atualizado com a data atual.

    Args:
        user_id (int): ID único do usuário.
        username (str): Nome de usuário para identificação.
        data_atual (str): Data atual formatada para ser usada no cadastro e último acesso.

    Returns:
        bool: True se a operação foi bem-sucedida, False caso contrário.
    """
    try:
        user = db["usuarios"].find_one({"user_id": user_id})
        if not user:
            db["usuarios"].insert_one({
                "user_id": user_id,
                "username": username,
                "cadastro": data_atual,
                "ultimo_acesso": data_atual,
            })
            logger.info(f"Novo usuário cadastrado: {user_id}")
        else:
            db["usuarios"].update_one({"user_id": user_id}, {"$set": {"ultimo_acesso": data_atual}})
            logger.debug(f"Último acesso atualizado para o usuário: {user_id}")
        return True
    except Exception as e:
        logger.error(f"Erro ao atualizar informações do usuário {user_id}: {e}", exc_info=True)
        return False

def util_display_control_buttons(user_id):
    """
    Exibe um conjunto de botões de controle para o usuário, dependendo do estado atual do quiz.

    Esta função cria uma interface de teclado personalizada para o usuário com base em seu progresso no quiz. 
    Se o usuário já respondeu todas as questões disponíveis, o botão "Finalizar Quiz" é exibido; 
    caso contrário, o botão "Próxima Questão" é mostrado.

    Args:
        user_id (int): ID do usuário que está interagindo com o bot.

    Returns:
        bool: True se os botões foram exibidos com sucesso, False caso contrário.
    """
    try:
        markup = types.ReplyKeyboardMarkup(row_width=2)

        if user_id in user_quiz_state:
            if user_quiz_state[user_id].get("respondidas", 0) == user_quiz_state[user_id].get("quantidade", 0):
                botao_proxima_questao = types.KeyboardButton("Finalizar Quiz")
            else:
                botao_proxima_questao = types.KeyboardButton("Próxima Questão")
        else:
            botao_proxima_questao = types.KeyboardButton("Próxima Questão")

        markup.add(
            botao_proxima_questao,
            types.KeyboardButton("Reportar Problema"),
            types.KeyboardButton("Solicitar Ajuda Extra!"),
            types.KeyboardButton("Solicitar Explicação Infantil!"),
            types.KeyboardButton("Criar Mnemônico!"),
            types.KeyboardButton("Analisar Plausibilidade")
        )
        util_send_message_with_retry(user_id, "OK! O que deseja fazer?", reply_markup=markup)
        logger.debug(f"Botões de controle exibidos para o usuário {user_id}")
        return True
    except Exception as e:
        logger.error(f"Erro ao exibir botões de controle para o usuário {user_id}: {e}", exc_info=True)
        return False

def util_send_message_with_retry(chat_id, text, reply_markup=None, parse_mode='Markdown', retries=3, delay=5):
    """
    Envia uma mensagem ao usuário, com suporte a reenvio automático em caso de falhas.

    Esta função tenta enviar uma mensagem ao usuário, dividindo-a em partes se ultrapassar o tamanho máximo permitido.
    Em caso de falha na conexão ou erro na API, ela tentará reenviar a mensagem um número específico de vezes, 
    com um atraso crescente entre as tentativas.

    Args:
        chat_id (int): ID do chat do usuário para o qual a mensagem será enviada.
        text (str): O texto da mensagem a ser enviada.
        reply_markup (Optional): Opções de marcação para personalizar o teclado (padrão é None).
        parse_mode (str): O modo de formatação do texto (padrão é 'Markdown').
        retries (int): Número máximo de tentativas de envio em caso de falha (padrão é 3).
        delay (int): Tempo de espera (em segundos) entre as tentativas de reenvio, com multiplicação por tentativa (padrão é 5).

    Returns:
        bool: True se a mensagem foi enviada com sucesso, False caso contrário.
    """
    MAX_MESSAGE_LENGTH = 4096

    parts = [text[i:i + MAX_MESSAGE_LENGTH] for i in range(0, len(text), MAX_MESSAGE_LENGTH)]

    for i, part in enumerate(parts, start=1):
        for attempt in range(1, retries + 1):
            try:
                bot.send_message(chat_id, part, reply_markup=reply_markup, parse_mode=parse_mode)
                logger.debug(f"Parte {i} da mensagem enviada com sucesso para o usuário {chat_id}")
                break
            except (requests.exceptions.ConnectTimeout, apihelper.ApiException) as e:
                if attempt < retries:
                    logger.warning(f"Tentativa {attempt} falhou ao enviar mensagem para o usuário {chat_id}. Erro: {e}")
                    time.sleep(delay * attempt)
                else:
                    logger.error(f"Falha ao enviar parte {i} da mensagem para o usuário {chat_id} após {retries} tentativas. Erro: {e}", exc_info=True)
                    return False
    return True

def format_plausibility_response(resposta_analise):
    logger.debug(f"=== Iniciando format_plausibility_response ===")
    logger.debug(f"Resposta recebida: {resposta_analise}")

    try:
        resposta_formatada = (
            f"*Explicação da Resposta Correta:*\n\n"
            f"{resposta_analise['correct_answer_explanation']}\n\n"
            f"*Análise das Alternativas Incorretas:*\n\n"
        )

        for i, analise in enumerate(resposta_analise['incorrect_answers_analysis']):
            logger.debug(f"Formatando análise da alternativa {i+1}: {analise}")
            try:
                plausibilidade = analise['plausibility'][0]['Plausibilidade']
                logger.debug(f"Plausibilidade da alternativa {i+1}: {plausibilidade}")
            except (IndexError, KeyError) as e:
                logger.error(f"Erro ao acessar plausibilidade da alternativa {i+1}: {e}. Análise: {analise}")
                plausibilidade = "Erro ao obter plausibilidade"

            resposta_formatada += (
                f"- *Alternativa:* {analise['alternative']}\n"
                f"  - *Plausibilidade:* {plausibilidade}\n"
                f"  - *Explicação:* {analise['explanation']}\n\n"
            )

        resposta_formatada += f"*Conclusão:*\n\n{resposta_analise['conclusion']}\n"
        logger.debug(f"Resposta formatada final: {resposta_formatada}")
        return resposta_formatada

    except Exception as e:
        logger.error(f"Erro inesperado em format_plausibility_response: {e}")
        return "Erro ao formatar a análise de plausibilidade."

def analyze_plausibility_A(questao, db, user_id):
    logger.debug(f"=== Iniciando analyze_plausibility_A para a questão {questao['_id']} ===")
    try:
        resposta_analise = None

        if "plausibilityAnalysis" in questao:
            logger.debug(f"Análise de plausibilidade encontrada na questão atual. Usando a análise existente.")
            resposta_analise = questao["plausibilityAnalysis"]

        if resposta_analise is None:
            logger.debug(f"Análise de plausibilidade não encontrada na questão atual. Buscando no banco de dados.")
            questao_completa = db["todasQuestoes"].find_one({"_id": questao["_id"]})
            if questao_completa and "plausibilityAnalysis" in questao_completa:
                logger.debug(f"Análise de plausibilidade encontrada no banco de dados.")
                resposta_analise = questao_completa["plausibilityAnalysis"]
                questao["plausibilityAnalysis"] = resposta_analise
            else:
                logger.debug(f"Análise de plausibilidade não encontrada no banco de dados.")

        if resposta_analise:
            logger.debug(f"Enviando análise de plausibilidade existente para o usuário.")
            resposta_formatada = format_plausibility_response(resposta_analise)
            util_send_message_with_retry(user_id, resposta_formatada, parse_mode="Markdown")
            logger.debug(f"Análise de plausibilidade enviada para o usuário {user_id}: {resposta_formatada}")
            return resposta_analise

        logger.debug(f"Gerando nova análise de plausibilidade usando a API OpenAI.")
        resposta_correta = questao['correct_answers'][0]
        prompt_analise_plausibilidade = f"""
        Análise de Plausibilidade:
        Questão: {questao['question']}
        Resposta Correta: {resposta_correta}
        Alternativas: {questao['answers']}

        Instruções: Por favor, preste atenção especial a termos de negação, como 'NÃO', 'EXCETO', 'MENOS', ao analisar as alternativas. Avalie a plausibilidade considerando que a questão pede para identificar uma exceção ou algo que não se enquadra no contexto esperado.
        """
        response = openai.ChatCompletion.create(
            model="gpt-4o-mini-2024-07-18",
            messages=[
                {
                    "role": "system",
                    "content": (
                        "Você é um expert em desenvolvimento e análise de questões para concurso público de múltipla escolha. "
                        "Seu objetivo principal é analisar quão plausíveis são as alternativas em responder corretamente ao enunciado. "
                        "Preste atenção especial a palavras-chave como 'NÃO', 'EXCETO', 'MENOS', que indicam que a questão pode estar pedindo uma exceção. "
                        "Sua análise deve considerar o contexto completo do enunciado e a relação entre as alternativas."
                    )
                },
                {"role": "user", "content": prompt_analise_plausibilidade}
            ]
        )

        logger.debug(f"Resposta bruta da OpenAI: {response}")
        resposta_analise = response['choices'][0]['message']['content']
        logger.debug(f"Conteúdo da resposta da OpenAI: {resposta_analise}")

        input_tokens = response['usage']['prompt_tokens']
        logger.info(f"Model Consumption: gpt-4o-mini-2024-07-18 - Análise de Plausibilidade | Direta - ID: {questao['_id']} - Input Tokens: {input_tokens}")

        output_tokens = response['usage']['completion_tokens']
        logger.info(f"Model Consumption: gpt-4o-mini-2024-07-18 - Análise de Plausibilidade | Direta - ID: {questao['_id']} - Output Tokens: {output_tokens}")

        if resposta_analise:

            try:
                resposta_analise = json.loads(resposta_analise)
                logger.debug(f"Resposta da OpenAI decodificada com sucesso: {resposta_analise}")
            except json.JSONDecodeError:
                logger.error(f"Erro ao decodificar a resposta JSON da OpenAI: {resposta_analise}")
                return None

            db["todasQuestoes"].update_one(
                {"_id": questao["_id"]},
                {"$set": {"plausibilityAnalysis": resposta_analise}} 
            )
            logger.debug(f"'plausibilityAnalysis' gerada e armazenada para a questão {questao['_id']}")

            resposta_formatada = format_plausibility_response(resposta_analise)
            util_send_message_with_retry(user_id, resposta_formatada, parse_mode="Markdown")
            logger.debug(f"Análise de plausibilidade formatada e enviada para o usuário {user_id}: {resposta_formatada}")

            return resposta_analise
        else:
            logger.warning(f"Resposta da OpenAI vazia para a questão {questao['_id']}.")

    except Exception as e:
        logger.error(f"Erro ao gerar análise de plausibilidade para a questão {questao['_id']}: {e}")
        return None

def analyze_plausibility_B(incorrect_alternatives):
    logger.debug(f"=== Iniciando analyze_plausibility_B ===")
    logger.debug(f"Alternativas incorretas recebidas: {incorrect_alternatives}")

    plausibility_levels = {
        "Improvável": 0,
        "Parcialmente plausível": 0.5,
        "Plausível": 1
    }
    
    if not incorrect_alternatives:
        logger.debug(f"Lista de alternativas incorretas vazia. Retornando 0.")
        return 0 

    total_plausibility = 0
    for alt in incorrect_alternatives:
        plausibility_level = plausibility_levels.get(alt['plausibility'][0]['Plausibilidade'], 0)
        logger.debug(f"Alternativa: {alt['alternative']}, Plausibilidade: {alt['plausibility'][0]['Plausibilidade']}, Nível: {plausibility_level}")
        total_plausibility += plausibility_level

    logger.debug(f"Plausibilidade total (antes da divisão): {total_plausibility}") # Log da plausibilidade total antes da divisão
    plausibility_index = (total_plausibility / len(incorrect_alternatives)) * 100
    logger.debug(f"Número de alternativas incorretas: {len(incorrect_alternatives)}") # Log do número de alternativas incorretas
    logger.debug(f"Plausibilidade total: {total_plausibility}, Índice de plausibilidade: {plausibility_index}")
    
    logger.debug(f"=== Fim de analyze_plausibility_B ===")
    return round(plausibility_index, 2)

def analyze_question_difficulty(questao, db, user_id):
    logger.debug(f"=== Iniciando analyze_question_difficulty para a questão {questao['_id']} ===")
    metrics = {}

    logger.debug(f"Verificando se 'plausibilityAnalysis' já existe para a questão {questao['_id']}")
    if "plausibilityAnalysis" not in questao:
        logger.debug(f"Gerando análise de plausibilidade para a questão {questao['_id']} para calcular a dificuldade")

        resposta_correta = questao['correct_answers'][0]

        prompt_analise_plausibilidade = f"""
        Análise de Plausibilidade:
        Questão: {questao['question']}
        Resposta Correta: {resposta_correta}
        Alternativas: {questao['answers']}

        Instruções: Por favor, preste atenção especial a termos de negação, como 'NÃO', 'EXCETO', 'MENOS', ao analisar as alternativas. Avalie a plausibilidade considerando que a questão pede para identificar uma exceção ou algo que não se enquadra no contexto esperado.
        """

        response = openai.ChatCompletion.create(
            model="gpt-4o-mini-2024-07-18",
            messages=[
                {
                    "role": "system",
                    "content": (
                        "Você é um expert em desenvolvimento e análise de questões para concurso público de múltipla escolha. "
                        "Seu objetivo principal é analisar quão plausíveis são as alternativas em responder corretamente ao enunciado. "
                        "Preste atenção especial a palavras-chave como 'NÃO', 'EXCETO', 'MENOS', que indicam que a questão pode estar pedindo uma exceção. "
                        "Sua análise deve considerar o contexto completo do enunciado e a relação entre as alternativas."
                    )
                },
                {"role": "user", "content": prompt_analise_plausibilidade}
            ],
            response_format={
                "type": "json_schema",
                "json_schema": {
                    "name": "plausibility_analysis_response",
                    "strict": True,
                    "schema": {
                        "type": "object",
                        "properties": {
                            "correct_answer_explanation": {"type": "string"},
                            "incorrect_answers_analysis": {
                                "type": "array",
                                "items": {
                                    "type": "object",
                                    "properties": {
                                        "alternative": {"type": "string"},
                                        "plausibility": {
                                            "type": "array",
                                            "items": {
                                                "type": "object",
                                                "properties": {
                                                    "Plausibilidade": {"type": "string", "enum": ["Improvável", "Parcialmente plausível", "Plausível"]},
                                                    "contextual_coherence": {"type": "string", "enum": ["Baixa", "Média", "Alta"]}
                                                },
                                                "required": ["Plausibilidade", "contextual_coherence"],
                                                "additionalProperties": False
                                            }
                                        },
                                        "explanation": {"type": "string"}
                                    },
                                    "required": ["alternative", "plausibility", "explanation"],
                                    "additionalProperties": False
                                }
                            },
                            "conclusion": {"type": "string"}
                        },
                        "required": ["correct_answer_explanation", "incorrect_answers_analysis", "conclusion"],
                        "additionalProperties": False
                    }
                }
            }
        )

        logger.debug(f"Resposta da OpenAI recebida: {response}")
        resposta_analise = response['choices'][0]['message']['content']

        input_tokens = response['usage']['prompt_tokens']
        logger.info(f"Model Consumption: gpt-4o-mini-2024-07-18 - Análise de Plausibilidade | Class. Dificuldade - ID: {questao['_id']} - Input Tokens: {input_tokens}")

        output_tokens = response['usage']['completion_tokens']
        logger.info(f"Model Consumption: gpt-4o-mini-2024-07-18 - Análise de Plausibilidade | Class. Dificuldade - ID: {questao['_id']} - Output Tokens: {output_tokens}")

        if resposta_analise:

            resposta_analise_objeto = json.loads(resposta_analise)

            db["todasQuestoes"].update_one(
                {"_id": questao["_id"]},
                {"$set": {"plausibilityAnalysis": resposta_analise_objeto}}
            )
            logger.debug(f"'plausibilityAnalysis' gerada e armazenada para a questão {questao['_id']}")

            questao = db["todasQuestoes"].find_one({"_id": questao["_id"]})
            logger.debug(f"Questão atualizada obtida do banco de dados: {questao}")
        else:
            logger.error(f"Falha ao gerar análise de plausibilidade para a questão {questao['_id']}")
            return None

    analyze_plausibility_A = questao.get("plausibilityAnalysis", {})
    logger.debug(f"Análise de plausibilidade obtida: {analyze_plausibility_A}")

    # Inicializar métricas com valores padrão
    metrics = {
        'plausibility_index': 0.5,
        'question_length': len(questao['question']),
        'avg_alternative_length': 0,
        'linguistic_complexity': 0.5,
        'key_concepts': 0,
        'has_numerical_data': 0,
        'abstraction_level': 0,
        'alternative_similarity': 0.5,
        'negation_count': 0
    }

    if 'incorrect_answers_analysis' in analyze_plausibility_A:
        logger.debug(f"Chave 'incorrect_answers_analysis' encontrada na análise de plausibilidade.")
        
        # 1. Índice de Plausibilidade
        metrics['plausibility_index'] = analyze_plausibility_B(analyze_plausibility_A['incorrect_answers_analysis'])
        
        # 2. Tamanho médio das alternativas
        if analyze_plausibility_A['incorrect_answers_analysis']:
            metrics['avg_alternative_length'] = sum(len(alt.get('alternative', '')) for alt in analyze_plausibility_A['incorrect_answers_analysis']) / len(analyze_plausibility_A['incorrect_answers_analysis'])
        
        # 3. Complexidade linguística
        try:
            blob = TextBlob(questao['question'])
            metrics['linguistic_complexity'] = blob.sentiment.subjectivity
        except Exception as e:
            logger.error(f"Erro ao calcular complexidade linguística: {e}")
        
        # 4. Número de conceitos-chave
        metrics['key_concepts'] = len(re.findall(r'\b[A-Z][a-z]+(?:\s+[A-Z][a-z]+)*\b', questao['question']))
        
        # 5. Presença de dados numéricos
        metrics['has_numerical_data'] = 1 if re.search(r'\d+', questao['question']) else 0
        
        # 6. Nível de abstração
        abstract_words = ['conceito', 'teoria', 'princípio', 'abstrato', 'filosófico']
        metrics['abstraction_level'] = sum(1 for word in abstract_words if word.lower() in questao['question'].lower())
        
        # 7. Similaridade entre alternativas
        all_alternatives = [questao['correct_answers'][0]] + [alt.get('alternative', '') for alt in analyze_plausibility_A['incorrect_answers_analysis']]
        if len(all_alternatives) > 1:
            similarity_scores = [SequenceMatcher(None, all_alternatives[i], all_alternatives[j]).ratio()
                                 for i in range(len(all_alternatives))
                                 for j in range(i+1, len(all_alternatives))]
            metrics['alternative_similarity'] = sum(similarity_scores) / len(similarity_scores) if similarity_scores else 0.5
        
        # 8. Presença de negações
        metrics['negation_count'] = questao['question'].lower().count('não') + questao['question'].lower().count('exceto')

    else:
        logger.warning(f"Chave 'incorrect_answers_analysis' ausente na análise de plausibilidade da questão {questao['_id']}. Usando análise simples.")

    # Ajuste dos pesos
    weights = {
        'plausibility_index': 0.6,  
        'question_length': 0.025,
        'avg_alternative_length': 0.025,  
        'linguistic_complexity': 0.05,
        'key_concepts': 0.05,
        'has_numerical_data': 0.05,
        'abstraction_level': 0.05, 
        'alternative_similarity': 0.1,
        'negation_count': 0.05
    }

    logger.debug(f"Pesos utilizados: {weights}")

    # Calcular o índice de dificuldade
    difficulty_index = sum(metrics[key] * weights[key] for key in weights if key in metrics)
    logger.debug(f"Índice de dificuldade: {difficulty_index}")

    # Normalizar o índice de dificuldade
    metrics['difficulty_index'] = round(difficulty_index / sum(weights.values()), 2)

    logger.debug(f"=== Fim de analyze_question_difficulty para a questão {questao['_id']} ===")
    logger.debug(f"Métricas calculadas: {metrics}")

    return metrics

def classify_difficulty(difficulty_index):
    logger.debug(f"=== Iniciando classify_difficulty ===")
    logger.debug(f"Índice de dificuldade recebido: {difficulty_index}")
    
    # Ajustar os thresholds para a nova escala
    thresholds = [
        (0.10, 'Muito Fácil'),
        (0.30, 'Fácil'),
        (0.50, 'Médio'),
        (0.70, 'Difícil'),
        (1.00, 'Muito Difícil')
    ]

    classificacao = next((level for threshold, level in thresholds if difficulty_index < threshold), 'Muito Difícil')
    logger.debug(f"Classificação de dificuldade: {classificacao} para o índice {difficulty_index}")
    logger.debug(f"=== Fim de classify_difficulty ===")

    return classificacao

def util_classify_and_update_difficulty(questao, db, user_id): 
    logger.debug(f"=== Iniciando util_classify_and_update_difficulty para a questão {questao['_id']} ===")

    try:
        # Verificar se a questão já foi classificada pela API
        if any(entry.get('metodo') == 'API' for entry in questao.get('log', [])):
            logger.debug(f"Questão {questao['_id']} já foi classificada pela API. Ignorando.")
            return

        # Analisar a dificuldade da questão
        metrics = analyze_question_difficulty(questao, db, user_id)
        if metrics is None:
            logger.error(f"Não foi possível analisar a dificuldade da questão {questao['_id']}")
            return

        logger.debug(f"Métricas recebidas de analyze_question_difficulty: {metrics}")
        nova_classificacao = classify_difficulty(metrics['difficulty_index'])
        logger.debug(f"Nova classificação de dificuldade: {nova_classificacao}")

        # Obter a dificuldade atual da questão
        dificuldade_atual = questao.get('level')
        logger.debug(f"Dificuldade atual da questão: {dificuldade_atual}")

        # Registrar a atualização no campo 'log'
        log_entry = {
            "data_hora": datetime.now(ZoneInfo("America/Sao_Paulo")),
            "campo": "level",
            "valor_antigo": dificuldade_atual,
            "novo_valor": nova_classificacao,
            "metodo": "API"
        }
        logger.debug(f"Entrada de log: {log_entry}")

        # Atualizar a questão no banco de dados
        result = db["todasQuestoes"].update_one(
            {"_id": ObjectId(questao['_id'])},
            {
                "$set": {"level": nova_classificacao},
                "$push": {"log": log_entry}
            }
        )

        if result.modified_count == 1:
            logger.debug(f"Questão {questao['_id']} atualizada com sucesso. Nova dificuldade: {nova_classificacao}")
        else:
            logger.warning(f"Questão {questao['_id']} não foi atualizada. Verifique o log para mais detalhes.")

    except Exception as e:
        logger.error(f"Erro ao classificar e atualizar dificuldade: {e}", exc_info=True)
    logger.debug(f"=== Fim de util_classify_and_update_difficulty para a questão {questao['_id']} ===")

def util_generate_contextual_hint(user_id):
    try:
        questao = user_quiz_state[user_id]['questao_atual']
        indice_dica = user_quiz_state[user_id]["indice_dica"]

        if "contextualHint" in questao and indice_dica < len(questao["contextualHint"]):
            dica_atual = questao["contextualHint"][indice_dica]
            logger.debug(f"Enviando dica {indice_dica + 1} para a questão {questao['_id']}")
            util_send_message_with_retry(user_id, f"*Dica {indice_dica + 1}:*\n\n{dica_atual}", parse_mode="Markdown")
            user_quiz_state[user_id]["indice_dica"] += 1
        else:
            dicas_anteriores = questao.get("contextualHint", [])
            prompt_ajuda_previa = f"""
            Enunciado da Questão:
            {questao['question']}

            Alternativas da Questão:
            {util_format_question_text(questao, user_quiz_state[user_id]["respostas_embaralhadas"])}

            Resposta Correta da Questão:
            {questao['correct_answers'][0]}

            Dicas Anteriores:
            {'; '.join(dicas_anteriores)}

            Instruções para a Nova Dica Contextual:
            1. Forneça UMA ÚNICA frase como dica contextual.
            2. A dica deve ser mais específica e relevante que as anteriores, progredindo em direção à resolução.
            3. Não repita informações já fornecidas nas dicas anteriores.
            4. Não forneça a resposta diretamente, apenas direcione o raciocínio.
            5. Mantenha a frase curta e concisa, preferencialmente não ultrapassando 30 palavras.
            6. Use Markdown para formatação, se necessário.
            """

            response = openai.ChatCompletion.create(
                model="gpt-4o-mini-2024-07-18",
                messages=[
                    {"role": "system", "content": "Você é um especialista em concursos públicos, com foco no cargo de Auditor Fiscal do Trabalho. Sua tarefa é fornecer uma dica contextual progressiva que ajude o candidato a pensar sobre a questão enviada, sem entregar a resposta diretamente."},
                    {"role": "user", "content": prompt_ajuda_previa}
                ]
            )

            logger.debug(f"Resposta da OpenAI recebida: {response}")
            nova_dica = response['choices'][0]['message']['content']

            input_tokens = response['usage']['prompt_tokens']
            logger.info(f"Model Consumption: gpt-4o-mini-2024-07-18 - Geração de Ajuda Prévia - ID: {questao['_id']} - Input Tokens: {input_tokens}")
            
            output_tokens = response['usage']['completion_tokens']            
            logger.info(f"Model Consumption: gpt-4o-mini-2024-07-18 - Geração de Ajuda Prévia - ID: {questao['_id']} - Output Tokens: {output_tokens}")


            db["todasQuestoes"].update_one(
                {"_id": questao["_id"]},
                {"$push": {"contextualHint": nova_dica}}
            )
            logger.debug(f"Nova dica gerada e adicionada ao 'contextualHint' da questão {questao['_id']}")

            util_send_message_with_retry(user_id, f"*Dica {indice_dica + 1}:*\n{nova_dica}", parse_mode="Markdown")
            user_quiz_state[user_id]["indice_dica"] += 1

        markup = util_create_answer_markup(len(questao['answers']))
        util_send_message_with_retry(user_id, "OK! O que deseja fazer?", reply_markup=markup)

    except Exception as e:
        logger.error(f"Erro ao gerar ajuda prévia para o usuário {user_id}: {e}")
        util_send_message_with_retry(user_id, "Desculpe, não consegui gerar dicas no momento. Tente novamente mais tarde.")

def util_generate_dr_sincero_hint(user_id):
    logger.debug("=== Entrando na função util_generate_dr_sincero_hint ===")
    try:
        logger.debug(f"Obtendo questão atual para o usuário {user_id}")
        questao = user_quiz_state[user_id]['questao_atual']

        if "drSincero" in questao:
            resposta_ajuda_sincera = questao["drSincero"]
            logger.debug(f"Utilizando 'drSincero' existente para questão {questao['_id']}")
        else:
            logger.debug(f"Construindo prompt para a OpenAI para a questão {questao['_id']}")
            prompt_ajuda_sincera = f"""
Questão:

{questao['question']}

Alternativas:

{util_format_question_text(questao, user_quiz_state[user_id]["respostas_embaralhadas"])}

Resposta Correta:

{questao['correct_answers'][0]}

Apresente uma análise honesta e direta sobre a questão e suas alternativas, considerando a alternativa correta na análise.
Não seja prolixo, evite longas respostas, menos de 300 tokens é o ideal.
Utilize um estilo de escrita sarcástico, direto e claro.
Evite referenciar diretamente a ordem das alternativas por números.
Responda utilizando Markdown para títulos/subtítulos (Negrito é apenas um * e não usar #).
"""
            logger.debug(f"Chamando API da OpenAI para gerar 'drSincero' para a questão {questao['_id']}")
            response = openai.ChatCompletion.create(
                model="gpt-4o-mini-2024-07-18",
                messages=[
                    {"role": "system", "content": "Você é um assistente sarcástico e direto."},
                    {"role": "user", "content": prompt_ajuda_sincera}
                ]
            )
            logger.debug(f"Resposta da OpenAI recebida: {response}")
            resposta_ajuda_sincera = response['choices'][0]['message']['content']

            # Logs de consumo de tokens separados
            input_tokens = response['usage']['prompt_tokens']
            logger.info(f"Model Consumption: gpt-4o-mini-2024-07-18 - Geração de Ajuda Dr. Sincero - ID: {questao['_id']} - Input Tokens: {input_tokens}")

            output_tokens = response['usage']['completion_tokens']
            logger.info(f"Model Consumption: gpt-4o-mini-2024-07-18 - Geração de Ajuda Dr. Sincero - ID: {questao['_id']} - Output Tokens: {output_tokens}")

            logger.debug(f"Atualizando questão {questao['_id']} no banco de dados com 'drSincero'")
            db["todasQuestoes"].update_one(
                {"_id": questao["_id"]}, 
                {"$set": {"drSincero": resposta_ajuda_sincera}}
            )
            logger.debug(f"'drSincero' gerado e armazenado para questão {questao['_id']}")

        resposta_ajuda_sincera_formatada = f"""*Análise Sincera:*\n\n{resposta_ajuda_sincera.strip()}"""

        partes_mensagem = util_split_message(resposta_ajuda_sincera_formatada)

        for parte in partes_mensagem:
            util_send_message_with_retry(user_id, parte, parse_mode="Markdown")

    except Exception as e:
        logger.error(f"Erro ao gerar ajuda sincera para o usuário {user_id}: {e}", exc_info=True)
        util_send_message_with_retry(user_id, "Desculpe, não consegui gerar ajuda sincera no momento. Tente novamente mais tarde.")

    finally:
        logger.debug("=== Saindo da função util_generate_dr_sincero_hint ===")
        # Reexibir o teclado de respostas ao invés dos botões de controle
        if user_id in user_quiz_state and "questao_atual" in user_quiz_state[user_id]:
            questao_atual = user_quiz_state[user_id]["questao_atual"]
            markup = util_create_answer_markup(len(questao_atual['answers']), incluir_dr_sincero=False)
            util_send_message_with_retry(user_id, "OK! O que deseja fazer?", reply_markup=markup)
        else:
            util_display_control_buttons(user_id)

def util_generate_pre_child_friendly_explanation(user_id):
    logger.debug("=== Entrando na função util_generate_pre_child_friendly_explanation ===")
    try:
        logger.debug(f"Obtendo questão atual para o usuário {user_id}")
        questao = user_quiz_state[user_id]['questao_atual']

        if "ajudaInfantil" in questao:
            resposta_ajuda_infantil = questao["ajudaInfantil"]
            logger.debug(f"Utilizando 'ajudaInfantil' existente para questão {questao['_id']}")
        else:
            logger.debug(f"Construindo prompt para a OpenAI para a questão {questao['_id']}")
            prompt_ajuda_infantil = f"""
Questão:

{questao['question']}

Alternativas:

{util_format_question_text(questao, user_quiz_state[user_id]["respostas_embaralhadas"])}

Reformule a pergunta e as alternativas de forma que uma criança de 10 anos consiga entender. Use uma linguagem simples e direta. Mantenha o contexto e o sentido da pergunta original.
"""
            logger.debug(f"Chamando API da OpenAI para gerar 'ajudaInfantil' para a questão {questao['_id']}")
            response = openai.ChatCompletion.create(
                model="gpt-4o-mini-2024-07-18",
                messages=[
                    {"role": "system", "content": "Você é um assistente que explica as coisas de forma simples e clara, como se estivesse falando com uma criança."},
                    {"role": "user", "content": prompt_ajuda_infantil}
                ]
            )
            logger.debug(f"Resposta da OpenAI recebida: {response}")
            resposta_ajuda_infantil = response['choices'][0]['message']['content']

            # Logs de consumo de tokens separados
            input_tokens = response['usage']['prompt_tokens']
            logger.info(f"Model Consumption: gpt-4o-mini-2024-07-18 - Geração de Ajuda Infantil - ID: {questao['_id']} - Input Tokens: {input_tokens}")

            output_tokens = response['usage']['completion_tokens']
            logger.info(f"Model Consumption: gpt-4o-mini-2024-07-18 - Geração de Ajuda Infantil - ID: {questao['_id']} - Output Tokens: {output_tokens}")

            logger.debug(f"Atualizando questão {questao['_id']} no banco de dados com 'ajudaInfantil'")
            db["todasQuestoes"].update_one(
                {"_id": questao["_id"]}, 
                {"$set": {"ajudaInfantil": resposta_ajuda_infantil}}
            )
            logger.debug(f"'ajudaInfantil' gerado e armazenado para questão {questao['_id']}")

        resposta_ajuda_infantil_formatada = f"""*Ajuda Infantil:*\n\n{resposta_ajuda_infantil.strip()}"""

        partes_mensagem = util_split_message(resposta_ajuda_infantil_formatada)

        for parte in partes_mensagem:
            util_send_message_with_retry(user_id, parte, parse_mode="Markdown")

    except Exception as e:
        logger.error(f"Erro ao gerar ajuda infantil para o usuário {user_id}: {e}", exc_info=True)
        util_send_message_with_retry(user_id, "Desculpe, não consegui gerar ajuda infantil no momento. Tente novamente mais tarde.")

    finally:
        logger.debug("=== Saindo da função util_generate_pre_child_friendly_explanation ===")
        # Reexibir o teclado de respostas ao invés dos botões de controle, sem a opção de ajuda infantil
        if user_id in user_quiz_state and "questao_atual" in user_quiz_state[user_id]:
            questao_atual = user_quiz_state[user_id]["questao_atual"]
            markup = util_create_answer_markup(len(questao_atual['answers']), incluir_ajuda_infantil=False)
            util_send_message_with_retry(user_id, "OK! O que deseja fazer?", reply_markup=markup)
        else:
            util_display_control_buttons(user_id)

def util_generate_extra_hint(user_id):
    logger.debug("=== Entrando na função util_generate_extra_hint ===")
    try:
        logger.debug(f"Obtendo questão atual para o usuário {user_id}")
        questao = user_quiz_state[user_id]['questao_atual']

        if "explanationExtra" in questao:
            resposta_ajuda_extra = questao["explanationExtra"]
            logger.debug(f"Utilizando 'explanationExtra' existente para questão {questao['_id']}")
        else:
            logger.debug(f"Construindo prompt para a OpenAI para a questão {questao['_id']}")
            prompt_ajuda_extra = f"""

            Questão:

            {questao['question']}

            Alternativas:

            {util_format_question_text(questao, user_quiz_state[user_id]["respostas_embaralhadas"])}

            Resposta Correta:

            {questao['correct_answers'][0]}

            Contexto Adicional:

            Explique os Conceitos-Chave da questão de forma didática e concisa, mas sem incluir 'Conceitos-Chave' como título.
            Forneça Exemplos Práticos e Reais para ilustrar os conceitos.
            Evite ser prolixo, mantenha a resposta curta e objetiva, menos de 300 tokens é o ideal.
            Responda utilizando Markdown para títulos/subtítulos (Negrito é apenas um * e não usar #).
            """

            logger.debug(f"Chamando API da OpenAI para gerar 'explanationExtra' para a questão {questao['_id']}")
            response = openai.ChatCompletion.create(
                model="gpt-4o-mini-2024-07-18",
                messages=[
                    {"role": "system", "content": "Você é um expert em concurso e na carreira de Auditor Fiscal do Trabalho. Sua tarefa é fornecer contextos relevantes para a questão a seguir, com o objetivo de esclarecer contextualmente a questão:"},
                    {"role": "user", "content": prompt_ajuda_extra}
                ]
            )

            logger.debug(f"Resposta da OpenAI recebida: {response}")
            resposta_ajuda_extra = response['choices'][0]['message']['content']

            # Logs de consumo de tokens separados
            input_tokens = response['usage']['prompt_tokens']
            logger.info(f"Model Consumption: gpt-4o-mini-2024-07-18 - Geração de Ajuda Extra - ID: {questao['_id']} - Input Tokens: {input_tokens}")

            output_tokens = response['usage']['completion_tokens']
            logger.info(f"Model Consumption: gpt-4o-mini-2024-07-18 - Geração de Ajuda Extra - ID: {questao['_id']} - Output Tokens: {output_tokens}")

            logger.debug(f"Atualizando questão {questao['_id']} no banco de dados com 'explanationExtra'")
            db["todasQuestoes"].update_one(
                {"_id": questao["_id"]}, 
                {"$set": {"explanationExtra": resposta_ajuda_extra}}
            )
            logger.debug(f"'explanationExtra' gerado e armazenado para questão {questao['_id']}")

        resposta_ajuda_extra_formatada =f"""*Conceitos-chave:*\n\n{resposta_ajuda_extra.split('*Exemplos reais:*')[0].strip()}"""

        partes_mensagem = util_split_message(resposta_ajuda_extra_formatada)

        for parte in partes_mensagem:
            util_send_message_with_retry(user_id, parte, parse_mode="Markdown")


    except Exception as e:
        logger.error(f"Erro ao gerar ajuda extra para o usuário {user_id}: {e}", exc_info=True)
        util_send_message_with_retry(user_id, "Desculpe, não consegui gerar ajuda extra no momento. Tente novamente mais tarde.")

    finally:
        logger.debug("=== Saindo da função util_generate_extra_hint ===")
        util_display_control_buttons(user_id)

def util_generate_pos_child_friendly_explanation(user_id):
    logger.debug("=== Entrando na função util_generate_pos_child_friendly_explanation ===")
    try:
        logger.debug(f"Obtendo questão atual para o usuário {user_id}")
        questao = user_quiz_state[user_id]['questao_atual']

        if "explicacaoInfantil" in questao:
            resposta_explicacao_infantil = questao["explicacaoInfantil"]
            logger.debug(f"Utilizando 'explicacaoInfantil' existente para questão {questao['_id']}")
        else:
            logger.debug(f"Construindo prompt para a OpenAI para a questão {questao['_id']}")
            prompt_explicacao_infantil = f"""
Questão:

{questao['question']}

Alternativas:

{util_format_question_text(questao, user_quiz_state[user_id]["respostas_embaralhadas"])}

Resposta Correta:

{questao['correct_answers'][0]}

Explicação:

Explique os conceitos envolvidos nesta questão como se estivesse falando com uma criança de 10 anos. Use uma linguagem simples, exemplos fáceis de entender e evite termos técnicos complicados. A resposta deve ser curta e direta, com menos de 300 tokens.
"""
            logger.debug(f"Chamando API da OpenAI para gerar 'explicacaoInfantil' para a questão {questao['_id']}")
            response = openai.ChatCompletion.create(
                model="gpt-4o-mini-2024-07-18",
                messages=[
                    {"role": "system", "content": "Você é um assistente que explica conceitos difíceis de forma simples e clara, como se estivesse falando com uma criança."},
                    {"role": "user", "content": prompt_explicacao_infantil}
                ]
            )

            logger.debug(f"Resposta da OpenAI recebida: {response}")
            resposta_explicacao_infantil = response['choices'][0]['message']['content']

            input_tokens = response['usage']['prompt_tokens']
            logger.info(f"Model Consumption: gpt-4o-mini-2024-07-18 - Geração de Explicação Infantil - ID: {questao['_id']} - Input Tokens: {input_tokens}")

            output_tokens = response['usage']['completion_tokens']
            logger.info(f"Model Consumption: gpt-4o-mini-2024-07-18 - Geração de Explicação Infantil - ID: {questao['_id']} - Output Tokens: {output_tokens}")

            logger.debug(f"Atualizando questão {questao['_id']} no banco de dados com 'explicacaoInfantil'")
            db["todasQuestoes"].update_one(
                {"_id": questao["_id"]}, 
                {"$set": {"explicacaoInfantil": resposta_explicacao_infantil}}
            )
            logger.debug(f"'explicacaoInfantil' gerado e armazenado para questão {questao['_id']}")

        resposta_explicacao_infantil_formatada = f"""*Explicação Infantil:*\n\n{resposta_explicacao_infantil.strip()}"""

        partes_mensagem = util_split_message(resposta_explicacao_infantil_formatada)

        for parte in partes_mensagem:
            util_send_message_with_retry(user_id, parte, parse_mode="Markdown")

    except Exception as e:
        logger.error(f"Erro ao gerar explicação infantil para o usuário {user_id}: {e}", exc_info=True)
        util_send_message_with_retry(user_id, "Desculpe, não consegui gerar explicação infantil no momento. Tente novamente mais tarde.")

    finally:
        logger.debug("=== Saindo da função util_generate_pos_child_friendly_explanation ===")
        util_display_control_buttons(user_id)

def util_generate_mnemonic(user_id):
    logger.debug("=== Entrando na função util_generate_mnemonic ===")
    try:
        logger.debug(f"Obtendo questão atual para o usuário {user_id}")
        questao = user_quiz_state[user_id]['questao_atual']

        if "mnemonico" in questao and questao["mnemonico"]:
            resposta_mnemonico = questao["mnemonico"]
            logger.debug(f"Utilizando mnemônico existente para questão {questao['_id']}")
        else:
            logger.debug(f"Construindo prompt para a OpenAI para a questão {questao['_id']}")
            prompt_mnemonico = f"""

            Questão:

            {questao['question']}

            Mnemônico:

            Crie um mnemônico curto, criativo e fácil de memorizar que resuma os conceitos-chave do contexto geral da questão.
            Utilize um estilo de escrita direto, claro e objetivo.
            Não faça referência direta a respostas ou alternativas específicas.
            Responda utilizando Markdown para títulos/subtítulos (Negrito é apenas um * e não usar #).
            """

            logger.debug(f"Chamando API da OpenAI para gerar mnemônico para a questão {questao['_id']}")
            response = openai.ChatCompletion.create(
                model="gpt-4o-mini-2024-07-18",
                messages=[
                    {"role": "system", "content": "Você é um expert em concursos e Sua tarefa é criar um mnemônico eficaz e memorável para ajudar a lembrar as informações essenciais do contexto geral da questão a seguir:"},
                    {"role": "user", "content": prompt_mnemonico}
                ]
            )

            logger.debug(f"Resposta da OpenAI recebida: {response}")
            resposta_mnemonico = response['choices'][0]['message']['content']

            input_tokens = response['usage']['prompt_tokens']
            logger.info(f"Model Consumption: gpt-4o-mini-2024-07-18 - Geração Mnemonico - ID: {questao['_id']} - Input Tokens: {input_tokens}")

            output_tokens = response['usage']['completion_tokens']
            logger.info(f"Model Consumption: gpt-4o-mini-2024-07-18 - Geração Mnemonico - ID: {questao['_id']} - Output Tokens: {output_tokens}")

            logger.debug(f"Atualizando questão {questao['_id']} no banco de dados com 'mnemonico'")
            db["todasQuestoes"].update_one(
                {"_id": questao["_id"]},
                {"$set": {"mnemonico": resposta_mnemonico}}
            )
            logger.debug(f"'mnemonico' gerado e armazenado para questão {questao['_id']}")

        resposta_mnemonico_formatada = (
            "*Mnemônico Questão:*\n\n"
            + resposta_mnemonico.strip()
        )

        partes_mensagem = util_split_message(resposta_mnemonico_formatada)

        for parte in partes_mensagem:
            util_send_message_with_retry(user_id, parte, parse_mode="Markdown")

    except Exception as e:
        logger.error(f"Erro ao gerar mnemônico para o usuário {user_id}: {e}", exc_info=True)
        util_send_message_with_retry(user_id, "Desculpe, não consegui gerar um mnemônico no momento. Tente novamente mais tarde.")

    finally:
        logger.debug("=== Saindo da função util_generate_mnemonic ===")
        util_display_control_buttons(user_id)

def fetch_quiz_questions(user_id, quantidade, concurso=None, prova=None, disciplina=None, fonte=None, shuffle_completo=False):
    """Busca questões para o quiz de acordo com as preferências do usuário."""
    fila_de_questoes = []
    try:
        logger.debug(f"Buscando {quantidade} questões para o usuário {user_id}. Concurso: {concurso}, Prova: {prova}, Disciplina: {disciplina}, Fonte: {fonte}, Random Geral: {shuffle_completo}")

        filtro = {}
        if not shuffle_completo:
            if concurso and concurso in [exam.value for exam in EXAMS_LIST]:
                filtro["concursos"] = concurso
            if disciplina:
                filtro["disciplina"] = disciplina
            if fonte and fonte != "Todas":
                if fonte == "Outras":
                    filtro["source"] = {"$nin": ["Resumo", "Legislação", "Jurisprudência", "Cesgranrio", "CESPE", "ESAF", "Q Concursos", "Estratégia", "Gran Concursos", "ChatGPT", "Gemini"]}
                else:
                    filtro["source"] = fonte

        # Buscar Todas as questões que atendem aos critérios
        todas_questoes = list(db["todasQuestoes"].aggregate([
            {"$match": filtro},
            {"$sample": {"size": quantidade}}
        ]))
        
        if not todas_questoes:
            logger.warning(f"Nenhuma questão encontrada para os critérios especificados. Usuário: {user_id}, Filtro: {filtro}")
            return []

        fila_de_questoes = todas_questoes

        logger.debug(f"Encontradas {len(fila_de_questoes)} de {quantidade} questões para o usuário {user_id}")

    except Exception as e:
        logger.error(f"Erro ao buscar questões para o quiz do usuário {user_id}: {e}", exc_info=True)

    return fila_de_questoes

def fetch_questions(user_id, concurso=None, prova=None, disciplina=None, fonte=None):
    """Busca uma questão aleatória do banco de dados."""
    try:
        logger.debug(f"Iniciando busca de questão para o usuário {user_id}, "
                     f"concurso: {concurso}, prova: {prova}, disciplina: {disciplina}, fonte: {fonte}")

        filtro = {}
        if concurso:
            filtro["concursos"] = concurso
            logger.debug(f"Filtro por concurso: {concurso}")
        if disciplina:
            filtro["disciplina"] = disciplina
            logger.debug(f"Filtro por disciplina: {disciplina}")
        if fonte:
            if fonte == "Todas":
                logger.debug("Filtro por fonte: Todas")
            elif fonte == "Outras":
                filtro["source"] = {"$nin": ["Resumo", "Legislação", "Jurisprudência", "Cesgranrio", "CESPE", "ESAF", "Q Concursos", "Estratégia", "Gran Concursos", "ChatGPT", "Gemini", "Todas"]}
                logger.debug("Filtro por fonte: Outras")
            else:
                filtro["source"] = fonte
                logger.debug(f"Filtro por fonte específica: {fonte}")

        questao_aleatoria = list(db["todasQuestoes"].aggregate([
            {"$match": filtro},
            {"$sample": {"size": 1}}
        ]))

        if questao_aleatoria:
            questao = questao_aleatoria[0]
            logger.debug(f"Questão {questao['_id']} encontrada para o usuário {user_id}")
            return questao
        else:
            logger.warning(f"Nenhuma questão encontrada para o usuário {user_id} "
                           f"com os filtros especificados.")
            return None

    except Exception as e:
        logger.error(f"Erro ao buscar questão para o usuário {user_id}: {str(e)}", exc_info=True)
        return None

def fetch_simulation_questions(user_id, qtDefinedAnswer):
    concurso = user_quiz_state[user_id].get("concurso")
    
    # Define a proporção correta para o concurso
    proporcao = SIMULATION_PROPORTIONS_CPNU if concurso == "CPNU" else SIMULATION_PROPORTIONS_BNDES

    disciplinas_disponiveis = list(proporcao.keys())
    questoes_simulado = []
    questoes_obtidas = 0
    
    logger.debug(f"Iniciando busca de {qtDefinedAnswer} questões para o simulado do usuário {user_id} no concurso {concurso}")
    logger.debug(f"Proporção inicial das subjects: {proporcao}")

    problemas_excluir = [
        "Alternativas Corretas em Duplicidade", 
        "Alternativas Incorretas", "Questão Completamente Problemática", 
        "Alternativas Mal Formuladas", "Outro", "Teste"
    ]

    try:
        questoes_dominadas_ids = set(q["question_id"] for q in db["userActivityLog"].find(
            {"user_id": user_id, "dominada": True},
            {"question_id": 1, "_id": 0}
        ))

        questoes_reportadas_ids = set(q["questao_id"] for q in db["reportedQuestoes"].find(
            {"fix.status_solved": False, "problemas": {"$in": problemas_excluir}},
            {"questao_id": 1, "_id": 0}
        ))

        while questoes_obtidas < qtDefinedAnswer and disciplinas_disponiveis:
            logger.debug(f"Faltam {qtDefinedAnswer - questoes_obtidas} questões. subjects disponíveis: {disciplinas_disponiveis}")

            questoes_por_disciplina = {d: max(1, int((qtDefinedAnswer - questoes_obtidas) * p)) for d, p in proporcao.items()}
            logger.debug(f"Questões por disciplina: {questoes_por_disciplina}")

            for disciplina in disciplinas_disponiveis.copy():
                quantidade = questoes_por_disciplina[disciplina]
                logger.debug(f"Buscando {quantidade} questões da disciplina {disciplina} no concurso {concurso}")

                filtro = {
                    "_id": {"$nin": list(questoes_dominadas_ids | questoes_reportadas_ids)},
                    "disciplina": disciplina,
                    "concursos": concurso
                }

                questoes = list(db["todasQuestoes"].aggregate([
                    {"$match": filtro},
                    {"$sample": {"size": quantidade}}
                ]))

                logger.debug(f"Encontradas {len(questoes)} questões para a disciplina {disciplina} no concurso {concurso}")
                questoes_simulado.extend(questoes)
                questoes_obtidas += len(questoes)

                if len(questoes) < quantidade:
                    logger.debug(f"Não há questões suficientes para {disciplina} no concurso {concurso}. Removendo da lista de subjects disponíveis.")
                    disciplinas_disponiveis.remove(disciplina)
                    peso_removido = proporcao.pop(disciplina)
                    if sum(proporcao.values()) > 0:
                        fator_ajuste = 1 / sum(proporcao.values())
                        proporcao = {d: p * fator_ajuste for d, p in proporcao.items()}
                        logger.debug(f"Nova proporção das subjects: {proporcao}")
                    else:
                        logger.warning("Todas as subjects foram removidas. Não é possível continuar o simulado.")
                        break

        random.shuffle(questoes_simulado)

        if questoes_obtidas < qtDefinedAnswer:
            logger.warning(f"Não foi possível obter a quantidade desejada de questões para o concurso {concurso}. Obtidas: {questoes_obtidas}, Desejadas: {qtDefinedAnswer}")

        logger.debug(f"Busca de questões concluída. Total de questões no simulado: {len(questoes_simulado)}")
        return questoes_simulado

    except Exception as e:
        logger.error(f"Erro ao buscar questões para o simulado do usuário {user_id} no concurso {concurso}: {str(e)}", exc_info=True)
        return []

def fetch_badass_questions(user_id, quantidade, modo_geral=False):
    """Busca as questões mais difíceis para o usuário, com base no histórico de respostas."""
    logger.debug(f"Iniciando busca de questões 'Most Badass' para o usuário {user_id}. Modo geral: {modo_geral}")

    disciplina = user_quiz_state[user_id].get("disciplina") if not modo_geral else None
    concurso = user_quiz_state[user_id].get("concurso") if not modo_geral else None

    pipeline = [
        {
            '$match': {
                'user_id': user_id,
                'dominada': False
            }
        }
    ]

    if disciplina:
        pipeline[0]['$match']['disciplina'] = disciplina

    pipeline.extend([
        {
            '$addFields': {
                'indice_acertos': {
                    '$divide': [
                        {'$subtract': [100, '$percentual_acertos']}, 100
                    ]
                },
                'indice_praticas': {
                    '$subtract': [
                        1, {'$divide': [1, {'$max': ['$quantidade_praticas', 1]}]}
                    ]
                },
                'indice_tempo': {
                    '$min': [{'$divide': ['$tempo_medio', 120]}, 1]
                }
            }
        },
        {
            '$addFields': {
                'indice_dificuldade': {
                    '$add': [
                        {'$multiply': ['$indice_acertos', 0.4]},
                        {'$multiply': ['$indice_praticas', 0.3]},
                        {'$multiply': ['$indice_tempo', 0.3]}
                    ]
                }
            }
        },
        {'$sort': {'indice_dificuldade': -1}},
        {
            '$lookup': {
                'from': 'todasQuestoes',
                'localField': 'question_id',
                'foreignField': '_id',
                'as': 'questao_completa'
            }
        },
        {
            '$unwind': '$questao_completa'
        }
    ])

    if concurso:
        pipeline.append({
            '$match': {
                'questao_completa.concursos': concurso
            }
        })

    pipeline.extend([
        {
            '$replaceRoot': {
                'newRoot': '$questao_completa'
            }
        },
        {'$limit': quantidade}
    ])

    logger.debug(f"Pipeline de agregação montado para buscar questões badass: {pipeline}")
    
    try:
        resultado = list(db["userActivityLog"].aggregate(pipeline))
        logger.debug(f"Resultado da agregação: {resultado}")
        
        if not resultado:
            logger.warning(f"Nenhuma questão 'badass' encontrada para o usuário {user_id}")
            return []
        
        questoes_badass = resultado
        logger.debug(f"Encontradas {len(questoes_badass)} questões badass para o usuário {user_id}")
        
        return questoes_badass[:quantidade]
    except pymongo.errors.PyMongoError as e:
        logger.error(f"Erro do MongoDB ao buscar questões badass para o usuário {user_id}: {e}", exc_info=True)
        return []
    except Exception as e:
        logger.error(f"Erro inesperado ao buscar questões badass para o usuário {user_id}: {e}", exc_info=True)
        return []

def util_get_random_question(user_id):
    """Obtém uma questão aleatória de toda a coleção todasQuestoes."""
    try:
        questao = list(db["todasQuestoes"].aggregate([{"$sample": {"size": 1}}]))
        return questao[0] if questao else None
    except Exception as e:
        logger.error(f"Erro ao obter questão Random Geral para o usuário {user_id}: {e}", exc_info=True)
        return None

def util_prepare_question(user_id, questao):
    global last_question, question_start_time
    last_question = questao
    question_start_time[user_id] = time.time()

    respostas_originais = questao.get("answers", [])
    if not respostas_originais:
        logger.warning(f"Questão {questao['_id']} não possui respostas.")
        return False

    respostas_embaralhadas = random.sample(respostas_originais, len(respostas_originais))
    mapeamento_indices = {i: respostas_originais.index(resposta) for i, resposta in enumerate(respostas_embaralhadas)}
    indices_corretos = [mapeamento_indices[i] for i in questao.get("correct_answer_indices", [])]

    user_quiz_state[user_id].update({
        "questao_atual": questao,
        "indices_corretos": indices_corretos,
        "respostas_embaralhadas": mapeamento_indices,
        "indice_dica": 0
    })

    logger.debug(f"Questão preparada para o usuário {user_id}: {questao['_id']}")
    return True

def util_send_formatted_question(user_id, questao):
    try:
        logger.debug(f"=== Chamando util_classify_and_update_difficulty para a questão {questao['_id']} ===")
        util_classify_and_update_difficulty(questao, db, user_id)
        logger.debug(f"=== util_classify_and_update_difficulty chamada com sucesso ===")

        header = util_format_question_header(user_id, questao)
        question_text = util_format_question_text(questao, user_quiz_state[user_id]["respostas_embaralhadas"], user_id)
        markup = util_create_answer_markup(len(questao.get('answers', [])))

        mensagem_completa = header + question_text
        util_send_message_with_retry(user_id, mensagem_completa, parse_mode="Markdown", reply_markup=markup)
        user_quiz_state[user_id]["questao_formatada"] = mensagem_completa
        
        logger.debug(f"Questão formatada e enviada para o usuário {user_id}: {questao['_id']}")
        return True

    except Exception as e:
        logger.error(f"Erro ao enviar questão formatada para o usuário {user_id}: {e}", exc_info=True)
        return False

def util_determine_quiz_mode(user_id):
    logger.debug(f"=== Entrando em util_determine_quiz_mode para o usuário {user_id} ===")
    logger.debug(f"Estado do quiz do usuário {user_id}: {user_quiz_state.get(user_id)}")

    if user_quiz_state[user_id].get("modo_quiz") == "Concurso Específico":
        logger.debug(f"Usuário {user_id} está no modo 'Concurso Específico'")
        return "Concurso Específico"
    elif user_quiz_state[user_id].get("simulado_mode"):
        logger.debug(f"Usuário {user_id} está no modo 'Simulado'")
        return "Simulado"
    elif user_quiz_state[user_id].get("shuffle_completo_mode"):
        logger.debug(f"Usuário {user_id} está no modo 'Random Completo'")
        return "Random Completo"
    elif user_quiz_state[user_id].get("badass_geral_mode"):
        logger.debug(f"Usuário {user_id} está no modo 'The Most Badass Geral'")
        return "The Most Badass Geral"
    elif user_quiz_state[user_id].get("shuffle_mode"):
        logger.debug(f"Usuário {user_id} está no modo 'Shuffle'")
        return "Shuffle"
    elif user_quiz_state[user_id].get("badass_mode"):
        logger.debug(f"Usuário {user_id} está no modo 'Most Badass'")
        return "Most Badass"
    elif user_quiz_state[user_id].get("single_mode"):
        logger.debug(f"Usuário {user_id} está no modo 'Single'")
        return "Single"
    else:
        logger.debug(f"Usuário {user_id} está no modo 'Concurso Específico' (padrão)")
        return "Concurso Específico"

    logger.debug(f"=== Saindo de util_determine_quiz_mode para o usuário {user_id} ===")

def util_format_question_header(user_id, questao):
    logger.debug(f"[INÍCIO] util_format_question_header para usuário {user_id}")
    logger.debug(f"[QUESTÃO] ID: {questao.get('_id', 'ID não disponível')}")
    logger.debug(f"[ESTADO INICIAL] user_quiz_state[{user_id}]: {user_quiz_state.get(user_id, 'Não existe')}")

    respondidas = user_quiz_state[user_id].get("respondidas", 0)
    qtDefinedAnswer = user_quiz_state[user_id].get("quantidade", 0)
    logger.debug(f"[PROGRESSO] Respondidas: {respondidas}, Total: {qtDefinedAnswer}")

    modo = util_determine_quiz_mode(user_id)
    logger.debug(f"[MODO] Determinado: {modo}")

    # Determinar o valor de 'prova' baseado no modo
    if modo in ['Single', 'Random Completo', 'The Most Badass Geral']:
        prova = 'N/A'
    else:
        prova = user_quiz_state[user_id].get("prova", "Geral")
    
    logger.debug(f"[PROVA] Determinada: {prova}")

    if prova == "Geral" and modo not in ['Single', 'Random Completo', 'The Most Badass Geral']:
        logger.warning(f"[ALERTA] Valor de 'prova' não encontrado para o usuário {user_id}")

    disciplina = questao.get('disciplina', 'Disciplina não especificada')
    level = questao.get('level', 'Nível não definido')
    logger.debug(f"[DETALHES] Disciplina: {disciplina}, Level: {level}")

    ultima_sessao = util_get_last_session_date(user_id, questao.get('_id'))
    logger.debug(f"[REVISÃO] Última data: {ultima_sessao}")

    num_respostas = util_get_answer_count(user_id, questao.get('_id'))
    taxa_acertos = util_get_success_rate(user_id, questao.get('_id'))
    logger.debug(f"[ESTATÍSTICAS] Número de respostas: {num_respostas}, Taxa de acertos: {taxa_acertos}")

    header = (
        "-----------------------------------------------------------------------------\n"
        f"Questão ID: {questao.get('_id', 'ID não disponível')} | {respondidas+1} / {qtDefinedAnswer}\n"
        f"Modo: {modo} | Prova: {prova}\n"
        f"Disciplina: {disciplina}\n"
        f"Nível de Dificuldade: {level}\n" 
        f"Última Sessão: {ultima_sessao}\n"
        f"Nº Respostas: {num_respostas if num_respostas is not None else 'N/A'} | % Acertos: {taxa_acertos}\n"
        "-----------------------------------------------------------------------------\n"
    )

    logger.debug(f"[HEADER] Formatado:\n{header}")
    logger.debug(f"[FIM] util_format_question_header para usuário {user_id}")

    return header

def util_format_question_text(questao, respostas_embaralhadas=None, user_id=None):
    texto = f"*{questao.get('question', 'Questão não disponível')}*\n\n"
    respostas = questao.get('answers', [])
    if respostas_embaralhadas and respostas:
        for i, indice_original in respostas_embaralhadas.items():
            texto += f"{i+1}. {respostas[indice_original]}\n\n"
    else:
        for i, resposta in enumerate(respostas):
            texto += f"{i+1}. {resposta}\n\n"
    
    logger.debug(f"Texto da questão formatado: {questao.get('_id', 'ID não disponível')}")
    return texto

def util_create_answer_markup(num_respostas, incluir_dr_sincero=True, incluir_ajuda_infantil=True):
    try:
        markup = types.ReplyKeyboardMarkup(row_width=1)
        botoes_respostas = [types.KeyboardButton(str(i + 1)) for i in range(num_respostas)]
        markup.add(*botoes_respostas)
        markup.add(types.KeyboardButton("Solicitar Ajuda!"))

        logger.debug(f"Markup de respostas criado com {num_respostas} opções e botão 'Solicitar Ajuda!'")
        return markup
    except Exception as e:
        logger.error(f"Erro ao criar markup de respostas: {e}", exc_info=True)
        return None

def util_create_help_markup(incluir_dr_sincero=True, incluir_ajuda_infantil=True):
    try:
        markup = types.ReplyKeyboardMarkup(row_width=1)
        markup.add(types.KeyboardButton("Solicitar Dicas"))
        
        if incluir_dr_sincero:
            markup.add(types.KeyboardButton("Solicitar Ajuda Dr. Sincero!"))
        
        if incluir_ajuda_infantil:
            markup.add(types.KeyboardButton("Solicitar Ajuda Infantil!"))

        logger.debug(f"Markup de ajuda criado com botões adicionais")
        return markup
    except Exception as e:
        logger.error(f"Erro ao criar markup de ajuda: {e}")
        return None

@bot.message_handler(func=lambda message: message.text == "Solicitar Ajuda!")
def handle_request_help(message):
    user_id = message.chat.id
    try:
        markup = util_create_help_markup()
        util_send_message_with_retry(user_id, "Escolha uma opção de ajuda:", reply_markup=markup)
        logger.debug(f"Submenu de ajuda exibido para o usuário {user_id}")
    except Exception as e:
        logger.error(f"Erro ao exibir submenu de ajuda para o usuário {user_id}: {e}")

def fetch_and_update_explanation(questao):
    try:
        if "explanation" not in questao or not questao["explanation"]:
            input_data = {
                "question": questao["question"],
                "choices": questao["answers"],
                "correct_answer": questao["correct_answers"][0]
            }

            input_json = json.dumps(input_data)
            input_tokens = model.count_tokens(input_json)
            logger.info(f"Model Consumption: gemini-1.5-flash - Geração de Explicação - ID: {questao['_id']} - Input Tokens: {input_tokens}")

            response = model.generate_content(input_json)
            explanation = response.text

            if explanation:
                output_tokens = model.count_tokens(explanation)
                logger.info(f"Model Consumption: gemini-1.5-flash - Geração de Explicação - ID: {questao['_id']} - Output Tokens: {output_tokens}")

                update_result = db["todasQuestoes"].update_one(
                    {"_id": questao["_id"]}, {"$set": {"explanation": explanation}}
                )
                if update_result.modified_count > 0:
                    logger.debug(f"Explanation updated in the database for question ID: {questao['_id']}")
                else:
                    logger.warning(f"No explanation was updated in the database for question ID: {questao['_id']}")
                questao["explanation"] = explanation
            else:
                questao["explanation"] = "Explicação gerada não disponível."
                logger.warning(f"Generated explanation not available for question ID: {questao['_id']}")
        else:
            logger.debug(f"Question ID: {questao['_id']} already has an explanation")
        return questao
    except genai.GenerativeAIError as e:
        logger.error(f"GenerativeAIError while fetching explanation for question ID: {questao['_id']}: {e}")
        return questao
    except pymongo.errors.PyMongoError as e:
        logger.error(f"PyMongoError while updating explanation for question ID: {questao['_id']}: {e}")
        return questao

def util_calculate_average_correct_answers(historico, n=3, limite_2=1.0, limite_3=0.65):
    historico_len = len(historico)
    logger.debug(f"Calculating accuracy average with history length: {historico_len}")

    if historico_len < 2:
        logger.debug("History length is less than 2, returning False")
        return False

    if historico_len == 2:
        result = sum(historico) / historico_len == limite_2
        logger.debug(f"History length is 2, result: {result}")
        return result

    ultimas_tentativas = historico[-n:]
    result = sum(ultimas_tentativas) / len(ultimas_tentativas) >= limite_3
    logger.debug(f"Last {n} attempts average calculation result: {result}")
    return result

def util_calculate_mastery(points):
    """Calcula se a questão é dominada com base nos pontos acumulados."""
    result = points >= 1
    logger.debug(f"Calculando se a questão é dominada: {result} (points={points})")
    return result

@bot.message_handler(commands=["interromper"])
def cmd_stop_quiz(message):
    """Interrompe o quiz atual do usuário."""
    user_id = message.chat.id
    try:
        if user_id in user_quiz_state:
            try:
                handle_quiz_completion(user_id)
            except KeyError as e:
                logger.error(f"Erro ao finalizar o quiz para o usuário {user_id}: {e}")
                util_send_message_with_retry(user_id, "Ocorreu um erro ao finalizar o quiz, mas ele foi interrompido.")
            finally:
                user_quiz_state.pop(user_id, None)  # Remove os dados do quiz do usuário de forma segura
            logger.debug(f"Quiz interrompido e finalizado para o usuário {user_id}")
        else:
            # Remove o teclado se o usuário estiver no menu inicial
            markup = types.ReplyKeyboardRemove(selective=False)
            util_send_message_with_retry(user_id, "Você não iniciou um quiz.", reply_markup=markup)
            logger.debug(f"Usuário {user_id} tentou interromper um quiz que não foi iniciado")
    except Exception as e:
        logger.error(f"Erro ao interromper quiz para o usuário {user_id}: {e}")
        util_send_message_with_retry(user_id, "Ocorreu um erro ao interromper o quiz. Por favor, tente novamente.")

def calcular_dias_desde_ultima_sessao(past_sessions: List[dict], data_atual: datetime) -> Tuple[int, Optional[int]]:
    """Calcula a diferença em dias desde a última sessão e a última revisão.

    Args:
        past_sessions (List[dict]): Lista de sessões passadas.
        data_atual (datetime): Data e hora atuais.

    Returns:
        Tuple[int, Optional[int]]: (dias_desde_ultima_sessao, dias_desde_ultima_revisao), onde:
            - dias_desde_ultima_sessao: Diferença em dias desde a última sessão (0 se for a primeira sessão).
            - dias_desde_ultima_revisao: Diferença em dias desde a última revisão (None se não houver revisão).
    """
    logger.debug(f"Calculando dias desde a última sessão e revisão.")

    dias_desde_ultima_sessao = 0
    dias_desde_ultima_revisao = None

    if past_sessions:
        ultima_sessao = past_sessions[-1]["data"]
        dias_desde_ultima_sessao = (data_atual.date() - ultima_sessao.date()).days
        logger.debug(f"Dias desde a última sessão: {dias_desde_ultima_sessao} dias.")

        # Itera pelas sessões em ordem reversa para encontrar a última revisão
        for session in reversed(past_sessions):
            if session.get("is_revision", False):
                ultima_revisao = session["data"]
                dias_desde_ultima_revisao = (data_atual.date() - ultima_revisao.date()).days
                logger.debug(f"Dias desde a última revisão: {dias_desde_ultima_revisao} dias.")
                break

    return dias_desde_ultima_sessao, dias_desde_ultima_revisao

def util_calculate_average_time(past_sessions: List[dict]) -> int:
    """Calcula o tempo médio de resposta em segundos a partir de uma lista de sessões,
    considerando apenas as sessões que possuem o campo 'tempo_gasto_segundos' válido.

    Args:
        past_sessions (List[dict]): Lista de dicionários contendo informações de cada sessão de resposta.

    Returns:
        int: Tempo médio de resposta em segundos, arredondado para o inteiro mais próximo.
    """
    logger.debug(f"Iniciando cálculo do tempo médio de resposta para {len(past_sessions)} sessões")
    
    sessões_com_tempo_valido = [
        session for session in past_sessions
        if isinstance(session.get('tempo_gasto_segundos'), (int, float))
    ]
    logger.debug(f"Número de sessões com tempo válido: {len(sessões_com_tempo_valido)}")
    
    if not sessões_com_tempo_valido:
        logger.debug("Nenhuma sessão válida encontrada. Retornando 0.")
        return 0
    
    total_tempo_segundos = sum(session["tempo_gasto_segundos"] for session in sessões_com_tempo_valido)
    logger.debug(f"Tempo total acumulado: {total_tempo_segundos} segundos")
    
    tempo_medio = round(total_tempo_segundos / len(sessões_com_tempo_valido))
    logger.debug(f"Tempo médio calculado: {tempo_medio} segundos")
    return tempo_medio

def util_get_success_rate(user_id, questao_id):
    logger.debug(f"Iniciando util_get_success_rate para usuário {user_id} e questão {questao_id}")
    try:
        count = db["userActivityLog"].count_documents({})
        logger.debug(f"Número de documentos na coleção userActivityLog: {count}")
        
        questao_usuario = db["userActivityLog"].find_one(
            {"user_id": user_id, "question_id": questao_id}
        )
        logger.debug(f"Resultado da busca no userActivityLog: {questao_usuario}")
        if questao_usuario:
            percentual_acertos = questao_usuario.get("percentual_acertos_acumulado") or questao_usuario.get("percentual_acertos")
            logger.debug(f"Percentual de acertos encontrado: {percentual_acertos}")
            if percentual_acertos is not None:
                resultado = f"{percentual_acertos:.2f}%"
                logger.debug(f"Retornando percentual formatado: {resultado}")
                return resultado
        logger.debug("Nenhum dado de percentual de acertos encontrado")
        return ""  # Retorna uma string vazia se não houver dados
    except Exception as e:
        logger.error(f"Erro ao obter percentual de acertos para a questão {questao_id} do usuário {user_id}: {e}")
        logger.debug("Retornando string vazia devido a erro")
        return ""  # Retorna uma string vazia em caso de erro

def util_calculate_accuracy_percentage(user_id: int) -> float:
    """
    Calcula o percentual de acertos para um usuário específico.
    """
    if user_id not in user_quiz_state:
        logger.warning(f"Usuário {user_id} não encontrado no user_quiz_state")
        return 0.0

    state = user_quiz_state[user_id]
    respondidas = state.get("respondidas", 0)
    acertos = state.get("acertos", 0)

    if respondidas == 0:
        logger.debug(f"Usuário {user_id} ainda não respondeu nenhuma questão")
        return 0.0

    percentual = (acertos / respondidas) * 100
    logger.debug(f"Percentual de acertos calculado para o usuário {user_id}: {percentual:.2f}%")
    return round(percentual, 2)

def calcular_percentual_acertos_acumulado(past_sessions, resposta_correta):
    total_sessoes = len(past_sessions) + 1
    acertos = sum(1 for sessao in past_sessions if sessao["status"]) + (1 if resposta_correta else 0)
    return (acertos / total_sessoes) * 100

def calcular_variacao_alternativa(past_sessions):
    alternativas_escolhidas = [s["alternativa_escolhida"] for s in past_sessions]
    return (len(set(alternativas_escolhidas)) - 1) / (len(alternativas_escolhidas) - 1) * 100 if len(alternativas_escolhidas) > 1 else 0

def calcular_frequencia_estudo(past_sessions, data_atual):
    if not past_sessions:
        return 1

    try:
        br_tz = ZoneInfo("America/Sao_Paulo")
        data_atual = data_atual.astimezone(br_tz)
        data_primeira_sessao = past_sessions[0]["data"].astimezone(br_tz)

        dias_desde_primeira_pratica = (data_atual.date() - data_primeira_sessao.date()).days
        meses_desde_primeira_pratica = max(dias_desde_primeira_pratica / DIAS_POR_MES, 1/DIAS_POR_MES)
        
        frequencia = len(past_sessions) / meses_desde_primeira_pratica
        return min(frequencia, 30)
    except Exception as e:
        logger.error(f"Erro ao calcular frequência de estudo: {e}")
        return 1

def util_calculate_confidence(past_sessions: List[dict], num_sessions: int = 3) -> Optional[int]:
    """Calcula a média do nível de confiança das últimas 'num_sessions' sessões.

    Args:
        past_sessions (List[dict]): Lista de dicionários contendo informações de cada sessão de resposta.
        num_sessions (int): Número de sessões a serem consideradas para o cálculo da média (padrão: 3).

    Returns:
        Optional[int]: Média do nível de confiança, arredondada para o inteiro mais próximo. 
                       Retorna None se não houver sessões suficientes.
    """
    logger.debug(f"Calculando a média do nível de confiança das últimas {num_sessions} sessões.")

    confidence_levels = [session.get('nivel_confianca') for session in past_sessions[-num_sessions:]]
    valid_levels = [level for level in confidence_levels if level is not None]
    
    if not valid_levels:
        logger.debug("Nenhum nível de confiança válido encontrado nas sessões.")
        return None
    
    average_confidence = sum(valid_levels) / len(valid_levels)
    logger.debug(f"Níveis de confiança válidos: {valid_levels}, Média: {average_confidence}")

    return round(average_confidence)

def calculate_streak_and_points(past_sessions, only_current_session=False):
    logger.debug(f"Iniciando cálculo de streak e pontos para {len(past_sessions)} sessões passadas")
    streak_atual = 0
    streak_max = 0
    points = 0
    streak = 0
    erro_consecutivo = 0

    # Pontuação progressiva para acertos
    points_correct = [0.2, 0.3, 0.5, 0.1]
    # Penalização ajustada para erros
    points_incorrect = [-0.2, -0.3, -0.5, -0.1]

    for index, session in enumerate(past_sessions):
        logger.debug(f"Processando sessão {index + 1}")
        
        if session['status']:  # Acerto
            streak += 1
            erro_consecutivo = 0
            logger.debug(f"Acerto. Novo streak: {streak}")
            if only_current_session and index == len(past_sessions) - 1:
                points = points_correct[min(streak - 1, 3)]
                logger.debug(f"Pontos da sessão atual (acerto): {points}")
        else:  # Erro
            erro_consecutivo += 1
            logger.debug(f"Erro detectado. Erros consecutivos: {erro_consecutivo}")
            if only_current_session and index == len(past_sessions) - 1:
                points = points_incorrect[min(erro_consecutivo - 1, 3)]
                logger.debug(f"Pontos da sessão atual (erro): {points}")
            streak = 0
            logger.debug(f"Streak resetado.")

        if not only_current_session:
            if session['status']:
                points += points_correct[min(streak - 1, 3)]
            else:
                points = max(0, points + points_incorrect[min(erro_consecutivo - 1, 3)])
            logger.debug(f"Pontos acumulados: {points}")

        streak_max = max(streak_max, streak)
        streak_atual = streak
        logger.debug(f"Streak máximo atualizado: {streak_max}")

    # Garantir que os pontos não sejam negativos
    points = max(0, points)
    logger.debug(f"Cálculo finalizado. Streak atual: {streak_atual}, Streak máximo: {streak_max}, Pontos: {points}")
    return streak_atual, streak_max, points

def calculate_decay(points, ultima_sessao, current_date):
    """
    Calcula o decay de pontos baseado no tempo desde a última sessão.
    """
    br_tz = ZoneInfo("America/Sao_Paulo")  # Define o fuso horário de São Paulo

    # Converte ultima_sessao para datetime aware com o fuso horário de São Paulo
    ultima_sessao = ultima_sessao.replace(tzinfo=br_tz) 

    dias_passados = (current_date - ultima_sessao).days
    taxa_decay_diaria = 0.01  # 1% de decay por dia
    
    pontos_restantes = points * (1 - taxa_decay_diaria) ** dias_passados
    
    return max(0, pontos_restantes)  # Garante que os pontos não fiquem negativos

def calculate_spaced_repetition_score(questao_usuario, tempo_gasto_segundos, nivel_confianca):
    """Calcula o score de repetição espaçada."""
    logger.debug(f"Calculando score de repetição espaçada para questão: {questao_usuario.get('_id')}")

    streak_atual = questao_usuario.get('streak_max', 0)
    tempo_medio_resposta = questao_usuario.get("tempos", {}).get('tempo_medio_resposta', 0)

    logger.debug(f"Dados extraídos - Streak atual: {streak_atual}, Tempo médio de resposta: {tempo_medio_resposta}")

    streak_factor = min(streak_atual / 5, 1)
    logger.debug(f"Fator de streak calculado: {streak_factor}")

    # Correção para evitar divisão por zero:
    if tempo_medio_resposta > 0:
        time_factor = max(0, min(2 - (tempo_gasto_segundos / tempo_medio_resposta), 1))
    else:
        time_factor = 0.5  # Valor padrão se não houver tempo médio disponível
    logger.debug(f"Fator de tempo calculado: {time_factor}")

    confidence_factor = nivel_confianca / 3
    logger.debug(f"Fator de confiança calculado: {confidence_factor}")

    score = (streak_factor * 0.4) + (time_factor * 0.2) + (confidence_factor * 0.4)
    logger.debug(f"Score final calculado: {score}")

    final_score = min(score, 1)
    logger.debug(f"Score final após limitação: {final_score}")

    return final_score

# Função para calcular spaced_repetition_interval
def calculate_spaced_repetition_interval(questao_usuario, tempo_gasto_segundos, nivel_confianca):
    """Calcula o intervalo de repetição espaçada."""
    logger.debug(f"Calculando intervalo de repetição espaçada para questão: {questao_usuario.get('_id')}")

    spaced_repetition_score = calculate_spaced_repetition_score(questao_usuario, tempo_gasto_segundos, nivel_confianca)
    logger.debug(f"Score de repetição espaçada calculado: {spaced_repetition_score}")

    min_interval = 3
    max_interval = 90
    base_interval = 10

    logger.debug(f"Parâmetros - Intervalo mínimo: {min_interval}, Intervalo máximo: {max_interval}, Intervalo base: {base_interval}")

    # Intervalo calculado em uma linha
    interval = max(min_interval, min(base_interval * (2**(spaced_repetition_score * 10 - 9)) if spaced_repetition_score >= 0.9 else base_interval * (1 + spaced_repetition_score), max_interval))

    logger.debug(f"Intervalo após aplicação dos limites: {interval}")

    resultado_intervalo = round(interval)
    logger.debug(f"Intervalo final após arredondamento: {resultado_intervalo}")

    return resultado_intervalo

def util_is_revision_session(questao_usuario):
    """Verifica se a sessão é uma sessão de revisão."""
    logger.debug(f"=== Entrando em util_is_revision_session ===")
    is_revision = questao_usuario is not None and questao_usuario.get('revisao_necessaria', False)
    logger.debug(f"É uma sessão de revisão? {is_revision}")
    logger.debug("=== Saindo de util_is_revision_session ===")
    return is_revision

def analyze_review_need(questao_usuario):
    logger.debug(f"=== Entrando em analyze_review_need ===")
    
    if questao_usuario is None:
        logger.debug("questao_usuario é None. Retornando True para revisão.")
        return True
    
    logger.debug(f"Analisando necessidade de revisão para questão: ID não disponível")

    past_sessions = questao_usuario.get("pastSessions", [])
    logger.debug(f"Número de sessões anteriores: {len(past_sessions)}")
    
    if not past_sessions:
        logger.debug("Nenhuma sessão anterior. Revisão necessária.")
        return True

    br_tz = ZoneInfo("America/Sao_Paulo")
    data_atual = datetime.now(br_tz)
    logger.debug(f"Data atual: {data_atual}")
    
    ultima_sessao = questao_usuario["datas"]["ultima_sessao"]
    logger.debug(f"Data da última sessão: {ultima_sessao}")
    
    dias_desde_ultima_sessao = (data_atual.date() - ultima_sessao.replace(tzinfo=br_tz).date()).days
    intervalo_espacado = questao_usuario.get('spaced_repetition_interval', 0)

    logger.debug(f"Dias desde a última sessão: {dias_desde_ultima_sessao}")
    logger.debug(f"Intervalo espaçado: {intervalo_espacado}")

    revisao_necessaria = dias_desde_ultima_sessao > intervalo_espacado

    logger.debug(f"Revisão necessária: {revisao_necessaria}")
    logger.debug(f"Motivo: {'Tempo desde última sessão maior que o intervalo espaçado' if revisao_necessaria else 'Nenhum'}")

    logger.debug("=== Saindo de analyze_review_need ===")

    return revisao_necessaria

def calcular_percentual_revisoes_sucesso(past_sessions):
    revisoes = [s for s in past_sessions if s.get("is_revision", False)]
    if revisoes:
        return sum(1 for r in revisoes if r["status"]) / len(revisoes) * 100
    return None

def update_user_question_performance(
    db,
    user_id: int,
    questao_id: str,
    resposta_correta: bool,
    disciplina: str,
    alternativa_escolhida: str,
    tempo_gasto_segundos: int,
    modo_sessao: str,
    modo_prova: str,
    concurso: Optional[str],
    nivel_confianca: int,
):
    """Atualiza o desempenho do usuário em uma questão no banco de dados."""
    logger.debug(f"=== Entrando em update_user_question_performance para o usuário {user_id} ===")
    logger.debug(f"Recebido para atualização: modo_sessao={modo_sessao}, modo_prova={modo_prova}, concurso={concurso}")

    try:
        br_tz = ZoneInfo("America/Sao_Paulo")
        data_atual = datetime.now(br_tz)
        session_uuid = user_quiz_state.get(user_id, {}).get("session_uuid")

        # Buscar documento existente ou criar um novo com valores padrão
        questao_usuario = db["userActivityLog"].find_one_and_update(
            {"user_id": user_id, "question_id": questao_id},
            {
                "$setOnInsert": {
                    "user_id": user_id,
                    "question_id": questao_id,
                    "datas": {
                        "ultima_sessao": data_atual,
                        "ultima_revisao": None,
                        "data_primeira_sessao": data_atual,
                        "data_primeiro_acerto": data_atual if resposta_correta else None,
                    },
                    "tempos": {
                        "tempo_total_estudo": 0,
                        "tempo_medio_acerto": 0,
                        "tempo_medio_erro": 0,
                        "tempo_medio_resposta": 0,
                    },
                    "disciplina": disciplina,
                    "pastSessions": [],
                    "quantidade_praticas": 0,
                    "quantidade_acertos": 0,
                    "quantidade_erros": 0,
                    "percentual_acertos": 0,
                    "percentual_revisoes_sucesso": None,
                    "var_alternativa_escolhida": 0,
                    "frequencia_estudo_mes": 0,
                    "spaced_repetition_interval": 0,
                    "spaced_repetition_score": 0,
                    "streak_atual": 0,
                    "streak_max": 0,
                    "points": 0,
                    "points_with_decay": 0,
                    "dominada": False,
                    "nivel_confianca_med_rec": 0,
                    "revisao_necessaria": False,
                }
            },
            upsert=True,
            return_document=ReturnDocument.AFTER
        )

        past_sessions = questao_usuario.get("pastSessions", [])

        # Lógica da nova sessão
        percentual_acertos_acumulado = calcular_percentual_acertos_acumulado(past_sessions, resposta_correta)
        total_tempo_gasto_segundos = questao_usuario["tempos"]["tempo_total_estudo"] + tempo_gasto_segundos
        tempo_medio_acumulado = total_tempo_gasto_segundos / (len(past_sessions) + 1)

        streak_atual_sessao, _, points_session = calculate_streak_and_points(past_sessions + [{"status": resposta_correta}], only_current_session=True)
        spaced_repetition_score_sessao = calculate_spaced_repetition_score(questao_usuario, tempo_gasto_segundos, nivel_confianca)
        spaced_repetition_interval_sessao = calculate_spaced_repetition_interval(questao_usuario, tempo_gasto_segundos, nivel_confianca)

        nova_sessao = {
            "data": data_atual,
            "status": resposta_correta,
            "alternativa_escolhida": alternativa_escolhida,
            "tempo_gasto_segundos": tempo_gasto_segundos,
            "session_uuid": session_uuid,
            "modo_sessao": modo_sessao,
            "modo_prova": modo_prova,
            "concurso": concurso,
            "dias_desde_ultima_sessao": 0,
            "dias_desde_ultima_revisao": None,
            "percentual_acertos_acumulado": percentual_acertos_acumulado,
            "tempo_medio_acumulado": tempo_medio_acumulado,
            "nivel_confianca": nivel_confianca,
            "spaced_repetition_interval": spaced_repetition_interval_sessao,
            "spaced_repetition_score": spaced_repetition_score_sessao,
            "streak_atual": streak_atual_sessao,
            "points_session": points_session
        }

        past_sessions.append(nova_sessao)

        streak_atual, streak_max, points = calculate_streak_and_points(past_sessions)
        spaced_repetition_score = calculate_spaced_repetition_score(questao_usuario, tempo_gasto_segundos, nivel_confianca)
        spaced_repetition_interval = calculate_spaced_repetition_interval(questao_usuario, tempo_gasto_segundos, nivel_confianca)
        var_alternativa_escolhida = calcular_variacao_alternativa(past_sessions)
        frequencia_estudo_mes = calcular_frequencia_estudo(past_sessions, data_atual)
        average_confidence = util_calculate_confidence(past_sessions)
        is_dominada = util_calculate_mastery(points)
        percentual_revisoes_sucesso = calcular_percentual_revisoes_sucesso(past_sessions)
        revisao_necessaria = analyze_review_need(questao_usuario)

        # Atualizar tempos
        tempo_total_estudo = questao_usuario["tempos"]["tempo_total_estudo"] + tempo_gasto_segundos
        
        if resposta_correta:
            tempo_medio_acerto = (
                (questao_usuario["tempos"]["tempo_medio_acerto"] * questao_usuario['quantidade_acertos'] + tempo_gasto_segundos) 
                / (questao_usuario['quantidade_acertos'] + 1)
            )
        else:
            tempo_medio_acerto = questao_usuario["tempos"]["tempo_medio_acerto"]

        tempo_medio_erro = (
            (questao_usuario["tempos"]["tempo_medio_erro"] * questao_usuario['quantidade_erros'] + tempo_gasto_segundos) 
            / (questao_usuario['quantidade_erros'] + (0 if resposta_correta else 1))
        ) if not resposta_correta else questao_usuario["tempos"]["tempo_medio_erro"]

        tempo_medio_resposta = tempo_total_estudo / (questao_usuario['quantidade_praticas'] + 1)

        points_with_decay = calculate_decay(points, questao_usuario['datas']['ultima_sessao'], data_atual)

        # Atualizar documento
        update_result = db["userActivityLog"].update_one(
            {"_id": questao_usuario["_id"]},
            {
                "$set": {
                    "datas.ultima_sessao": data_atual,
                    "datas.ultima_revisao": questao_usuario["datas"].get("ultima_revisao"),
                    "datas.data_primeira_sessao": questao_usuario["datas"]["data_primeira_sessao"],
                    "datas.data_primeiro_acerto": data_atual if resposta_correta and questao_usuario["datas"]["data_primeiro_acerto"] is None else questao_usuario["datas"]["data_primeiro_acerto"],
                    "tempos.tempo_total_estudo": tempo_total_estudo,
                    "tempos.tempo_medio_acerto": tempo_medio_acerto,
                    "tempos.tempo_medio_erro": tempo_medio_erro,
                    "tempos.tempo_medio_resposta": tempo_medio_resposta,
                    "disciplina": disciplina,
                    "percentual_acertos": percentual_acertos_acumulado,
                    "nivel_confianca_med_rec": average_confidence,
                    "var_alternativa_escolhida": var_alternativa_escolhida,
                    "frequencia_estudo_mes": frequencia_estudo_mes,
                    "spaced_repetition_score": spaced_repetition_score, 
                    "spaced_repetition_interval": spaced_repetition_interval,
                    "dominada": is_dominada,
                    "percentual_revisoes_sucesso": percentual_revisoes_sucesso,
                    "streak_atual": streak_atual,
                    "streak_max": streak_max,
                    "revisao_necessaria": revisao_necessaria,
                    "points": max(0, points),
                    "points_with_decay": points_with_decay,
                },
                "$push": {"pastSessions": nova_sessao},
                "$inc": {
                    "quantidade_praticas": 1,
                    "quantidade_acertos": 1 if resposta_correta else 0,
                    "quantidade_erros": 0 if resposta_correta else 1,
                },
            }
        )

        logger.debug(f"Documento atualizado para o usuário {user_id}, questão {questao_id}")
        logger.debug(f"Desempenho atualizado para a questão {questao_id} do usuário {user_id}. Percentual de acertos acumulado: {percentual_acertos_acumulado}%")

        return True

    except Exception as e:
        logger.error(f"Erro ao atualizar o desempenho do usuário {user_id} na questão {questao_id}: {e}", exc_info=True)
        return False

def get_user_points(db, user_id, with_decay=False):
    """Retorna o total de pontos do usuário, com ou sem decay."""
    pipeline = [
        {"$match": {"user_id": user_id}},
        {"$group": {
            "_id": None,
            "total_points": {"$sum": "$points_with_decay" if with_decay else "$points"}
        }}
    ]
    
    result = list(db["userActivityLog"].aggregate(pipeline))
    
    if result:
        return result[0]["total_points"]
    else:
        return 0

@bot.message_handler(commands=["report"])
def cmd_report_problem(message):
    user_id = message.chat.id
    global last_answered_question

    try:
        if user_id in last_answered_question:
            markup = util_create_markup(VALID_PROBLEM_LIST, row_width=1, include_cancel=True)
            util_send_message_with_retry(user_id, "Selecione o problema encontrado na última questão:", reply_markup=markup)
            user_quiz_state.setdefault(user_id, {})["reportando_problema"] = True
            user_quiz_state[user_id]["ultima_questao_reportada"] = last_answered_question[user_id]
            logger.debug(f"Usuário {user_id} reportando problema na última questão respondida: {last_answered_question[user_id]['_id']}")
        elif user_id in user_quiz_state and user_quiz_state[user_id].get("quiz_finalizado"):
            last_question = user_quiz_state[user_id].get("last_question")
            if last_question:
                markup = util_create_markup(VALID_PROBLEM_LIST, row_width=1)
                util_send_message_with_retry(user_id, "Selecione o problema encontrado na última questão:", reply_markup=markup)
                user_quiz_state[user_id]["reportando_problema"] = True
                user_quiz_state[user_id]["ultima_questao_reportada"] = last_question
                logger.debug(f"Usuário {user_id} reportando problema na última questão do quiz finalizado: {last_question['_id']}")
            else:
                bot.reply_to(message, "Nenhuma questão foi respondida recentemente para ser reportada.")
                logger.debug(f"Usuário {user_id} tentou reportar problema, mas nenhuma questão recente foi encontrada")
        else:
            bot.reply_to(message, "Nenhuma questão foi respondida recentemente para ser reportada.")
            logger.debug(f"Usuário {user_id} tentou reportar problema, mas nenhuma questão recente foi encontrada")
    except Exception as e:
        logger.error(f"Erro ao iniciar o relatório de problema para o usuário {user_id}: {e}")

@bot.message_handler(func=lambda message: message.text == "Solicitar Dicas")
def handle_hint_request(message):
    user_id = message.chat.id
    if user_id in user_quiz_state:
        util_generate_contextual_hint(user_id)  # Envia a dica prévia
        # Manter o teclado de respostas ativo
        markup = util_create_answer_markup(len(user_quiz_state[user_id]["questao_atual"]['answers']))

@bot.message_handler(func=lambda message: message.text == "Solicitar Ajuda Dr. Sincero!")
def handle_dr_sincero_help_request(message):
    user_id = message.chat.id
    logger.debug(f"=== Handler handle_dr_sincero_help_request chamado para usuário {user_id} ===")
    if user_id in user_quiz_state:
        logger.debug(f"Chamando util_generate_dr_sincero_hint para usuário {user_id} e questão {user_quiz_state[user_id]['questao_atual']['_id']}")
        util_generate_dr_sincero_hint(user_id)
    else:
        logger.debug(f"Usuário {user_id} não encontrado em user_quiz_state")

@bot.message_handler(func=lambda message: message.text == "Solicitar Ajuda Infantil!")
def handle_child_friendly_hint_request(message):
    user_id = message.chat.id
    logger.debug(f"=== Handler handle_child_friendly_hint_request chamado para usuário {user_id} ===")
    if user_id in user_quiz_state:
        logger.debug(f"Chamando util_generate_pre_child_friendly_explanation para usuário {user_id} e questão {user_quiz_state[user_id]['questao_atual']['_id']}")
        util_generate_pre_child_friendly_explanation(user_id)
    else:
        logger.debug(f"Usuário {user_id} não encontrado em user_quiz_state")

@bot.message_handler(func=lambda message: message.text == "Solicitar Explicação Infantil!")
def handle_child_friendly_explanation_request(message):
    user_id = message.chat.id
    logger.debug(f"=== Handler handle_child_friendly_explanation_request chamado para usuário {user_id} ===") 
    if user_id in user_quiz_state:
        logger.debug(f"Chamando util_generate_pos_child_friendly_explanation para usuário {user_id} e questão {user_quiz_state[user_id]['questao_atual']['_id']}")
        util_generate_pos_child_friendly_explanation(user_id)
    else:
        logger.debug(f"Usuário {user_id} não encontrado em user_quiz_state")

def util_save_reported_problem(user_id, questao_id, problema):
    """Salva um problema reportado pelo usuário para uma questão específica.

    Args:
        user_id (int): ID do usuário.
        questao_id (str): ID da questão.
        problema (str): Descrição do problema.
    """
    logger.debug(f"Usuário {user_id} reportou problema: {problema} para a questão {questao_id}")
    logger.debug("=== Entrando em util_save_reported_problem ===")

    data_atual = datetime.now(ZoneInfo("America/Sao_Paulo"))

    if problema not in VALID_PROBLEM_LIST:
        util_send_message_with_retry(user_id, "Problema não reconhecido. Por favor, escolha uma das opções fornecidas.")
        logger.warning(f"Usuário {user_id} tentou reportar um problema inválido: {problema}")
        return

    try:
        # Inicializa "questoes_reportadas" se não existir
        if user_id not in user_quiz_state:
            user_quiz_state[user_id] = {}
        if "questoes_reportadas" not in user_quiz_state[user_id]:
            user_quiz_state[user_id]["questoes_reportadas"] = 0

        logger.debug(f"Buscando relatório existente para a questão {questao_id} e usuário {user_id}")
        existing_report = db["reportedQuestoes"].find_one({"questao_id": questao_id, "user_id": user_id})

        mensagem_enviada = False

        if existing_report:
            logger.debug(f"Relatório existente encontrado: {existing_report}")
            if problema not in existing_report.get("problemas", []):
                logger.debug(f"Adicionando problema {problema} ao relatório existente")
                db["reportedQuestoes"].update_one(
                    {"questao_id": questao_id, "user_id": user_id},
                    {
                        "$push": {"problemas": problema},
                        "$set": {"ultima_atualizacao": data_atual}
                    }
                )
                logger.debug(f"Problema {problema} adicionado ao relatório existente para a questão {questao_id} pelo usuário {user_id}")
                mensagem_enviada = True
        else:
            logger.debug(f"Criando novo relatório para a questão {questao_id} e usuário {user_id}")
            db["reportedQuestoes"].insert_one({
                "user_id": user_id,
                "questao_id": questao_id,
                "problemas": [problema],
                "data_report": data_atual,
                "ultima_atualizacao": data_atual,
                "fix": {
                    "data_solved": None,
                    "status_solved": False
                }
            })
            logger.debug(f"Novo problema {problema} reportado para a questão {questao_id} pelo usuário {user_id}")
            mensagem_enviada = True

        user_quiz_state[user_id]["questoes_reportadas"] += 1
        logger.debug(f"Contador de questões reportadas incrementado para o usuário {user_id}")

        # Lógica para remover a questão da fila e buscar uma nova
        if user_quiz_state[user_id].get("simulado_mode"):
            util_handle_simulado_mode(user_id, questao_id)
        else:
            util_handle_regular_mode(user_id, questao_id)

    except Exception as e:
        logger.error(f"Erro ao salvar o problema reportado pelo usuário {user_id} para a questão {questao_id}: {e}", exc_info=True)
    finally:
        logger.debug("=== Saindo de util_save_reported_problem ===")
        if user_id in user_quiz_state:
            user_quiz_state[user_id]["reportando_problema"] = False
            user_quiz_state[user_id]["esperando_resposta"] = False
            logger.debug(f"Flags 'reportando_problema' e 'esperando_resposta' resetadas para o usuário {user_id}")

def util_handle_simulado_mode(user_id, questao_id):
    logger.debug(f"Entrando na lógica de remoção para o modo simulado. questoes_simulado: {user_quiz_state[user_id]['questoes_simulado']}")
    for i, q in enumerate(user_quiz_state[user_id]["questoes_simulado"]):
        if q['_id'] == questao_id:
            logger.debug(f"Encontrou a questão {questao_id} no índice {i}. Removendo...")
            user_quiz_state[user_id]["questoes_simulado"].pop(i)
            disciplina_original = q['disciplina']
            nova_questao = fetch_questions(user_id, disciplina=disciplina_original) or fetch_questions(user_id)
            if nova_questao:
                user_quiz_state[user_id]["questoes_simulado"].insert(i, nova_questao)
                logger.debug(f"Nova questão {nova_questao['_id']} adicionada à lista de simulado no índice {i} para o usuário {user_id}")
            else:
                logger.error(f"Não foi possível encontrar uma nova questão para substituir a questão reportada. O quiz pode terminar antes do previsto.")
            break

def util_handle_regular_mode(user_id, questao_id):
    if any(q['_id'] == questao_id for q in user_quiz_state[user_id]["fila_de_questoes"]):
        user_quiz_state[user_id]["fila_de_questoes"] = [
            q for q in user_quiz_state[user_id]["fila_de_questoes"] if q['_id'] != questao_id
        ]
        logger.debug(f"Questão {questao_id} removida de 'fila_de_questoes' para o usuário {user_id}")
        nova_questao = fetch_questions(user_id)
        if nova_questao:
            user_quiz_state[user_id]["fila_de_questoes"].append(nova_questao)
            logger.debug(f"Nova questão {nova_questao['_id']} adicionada à fila para o usuário {user_id}")

@bot.message_handler(func=lambda message: message.text in VALID_PROBLEM_LIST or message.text == "Reportar Problema")
def handle_problem_reporting(message):
    user_id = message.chat.id
    problema_reportado = message.text

    logger.debug("=== Entrando em handle_problem_reporting ===")  # DEBUG
    
    try:
        if message.text == "Reportar Problema":
            if user_id in last_answered_question:
                questao_id = last_answered_question[user_id]['_id'] 
                # Inicia o processo de reportar o problema para a questao_id
                markup = util_create_markup(VALID_PROBLEM_LIST, row_width=1, include_cancel=True)
                util_send_message_with_retry(user_id, "Selecione o problema encontrado na última questão:", reply_markup=markup)

                if user_id not in user_quiz_state:
                    user_quiz_state[user_id] = {}
                user_quiz_state[user_id]["reportando_problema"] = True
                user_quiz_state[user_id]["ultima_questao_reportada"] = last_answered_question[user_id]
                logger.debug(f"Usuário {user_id} reportando problema na última questão respondida: {last_answered_question[user_id]['_id']}")

            else:
                util_send_message_with_retry(user_id, "Nenhuma questão foi respondida recentemente para ser reportada.")
                logger.debug(f"Usuário {user_id} tentou reportar problema, mas nenhuma questão recente foi encontrada")
                return

        else: # O usuário selecionou um problema da lista
            logger.debug("=== Usuário selecionou um problema da lista ===")  # DEBUG
            questao_id = user_quiz_state[user_id].get("ultima_questao_reportada", {}).get("_id")

            if questao_id:
                util_save_reported_problem(user_id, questao_id, problema_reportado)
                logger.debug(f"Problema '{problema_reportado}' reportado para a questão {questao_id} pelo usuário {user_id}")
                
                logger.debug("=== Chamando util_display_control_buttons em handle_problem_reporting ===")  # DEBUG
                util_display_control_buttons(user_id)

            else:
                util_send_message_with_retry(user_id, "Nenhuma questão foi encontrada para ser reportada.")
                logger.warning(f"Usuário {user_id} tentou reportar um problema, mas nenhuma questão foi encontrada")

    except Exception as e:
        logger.error(f"Erro ao lidar com a reportagem de problema para o usuário {user_id}: {e}")
    finally:
        logger.debug("=== Saindo de handle_problem_reporting ===")  # DEBUG

@bot.message_handler(func=lambda message: message.text == "Cancelar Report")
def handle_cancel_report(message):
    """
    Lida com o cancelamento de um reporte de problema pelo usuário.

    Esta função é acionada quando o usuário escolhe a opção "Cancelar Report" durante o processo de reporte de problema.
    Se o usuário estiver no meio de um reporte, o status `reportando_problema` é atualizado para `False`,
    e o fluxo normal do quiz é retomado, exibindo os botões de controle.

    Args:
        message (telebot.types.Message): A mensagem recebida do usuário.

    Returns:
        None
    """
    user_id = message.chat.id
    if user_id in user_disciplina:
        user_disciplina[user_id]["reportando_problema"] = False
        util_send_message_with_retry(user_id, "Reporte de problema cancelado, fluxo retomado.", reply_markup=util_display_control_buttons(user_id))
        logger.debug(f"Usuário {user_id} cancelou o reporte do problema.")

@bot.message_handler(func=lambda message: message.text == "Analisar Plausibilidade")
def handle_plausibility_analysis(message):
    user_id = message.chat.id
    logger.debug(f"=== Handler handle_plausibility_analysis chamado para usuário {user_id} ===")
    if user_id in user_quiz_state:
        logger.debug(f"Chamando analyze_plausibility_A para usuário {user_id} e questão {user_quiz_state[user_id]['questao_atual']['_id']}")

        # Chama a função analyze_plausibility_A para gerar e exibir a análise
        analyze_plausibility_A(user_quiz_state[user_id]['questao_atual'], db, user_id)

    else:
        logger.debug(f"Usuário {user_id} não encontrado em user_quiz_state")
        util_send_message_with_retry(user_id, "Você precisa iniciar um quiz antes de analisar a plausibilidade.")

@bot.message_handler(func=lambda message: message.text == "Solicitar Ajuda Extra!")
def handle_extra_help_request(message):
    user_id = message.chat.id
    logger.debug(f"=== Handler handle_extra_help_request chamado para usuário {user_id} ===") 
    if user_id in user_quiz_state:
        logger.debug(f"Chamando util_generate_extra_hint para usuário {user_id} e questão {user_quiz_state[user_id]['questao_atual']['_id']}")
        util_generate_extra_hint(user_id)
    else:
        logger.debug(f"Usuário {user_id} não encontrado em user_quiz_state")

@bot.message_handler(func=lambda message: message.text == "Criar Mnemônico!")
def handle_mnemonic_request(message):
    user_id = message.chat.id
    logger.debug(f"=== Handler handle_mnemonic_request chamado para usuário {user_id} ===")
    if user_id in user_quiz_state:
        logger.debug(f"Chamando util_generate_mnemonic para usuário {user_id} e questão {user_quiz_state[user_id]['questao_atual']['_id']}")
        util_generate_mnemonic(user_id)
    else:
        logger.debug(f"Usuário {user_id} não encontrado em user_quiz_state")


def util_redisplay_current_question(user_id):
    try:
        if "questao_formatada" in user_quiz_state[user_id]:
            util_send_message_with_retry(user_id, user_quiz_state[user_id]["questao_formatada"], parse_mode="Markdown")
            logger.debug(f"Questão atual reexibida para o usuário {user_id}")
        else:
            send_next_question(user_id)
            logger.debug(f"Próxima questão enviada para o usuário {user_id} na reexibição")
    except Exception as e:
        logger.error(f"Erro ao reexibir questão atual para o usuário {user_id}: {e}")

def util_get_last_session_date(user_id, questao_id):
    try:
        questao_usuario = db["userActivityLog"].find_one(
            {"user_id": user_id, "question_id": questao_id}
        )
        ultima_sessao = questao_usuario["datas"]["ultima_sessao"].strftime("%d/%m/%Y %H:%M:%S") if questao_usuario else "N/A"
        logger.debug(f"Última sessão obtida para a questão {questao_id} do usuário {user_id}: {ultima_sessao}")
        return ultima_sessao
    except Exception as e:
        logger.error(f"Erro ao obter sessão revisão para a questão {questao_id} do usuário {user_id}: {e}")
        return "N/A"

def util_get_last_revision_date(user_id, questao_id):
    try:
        questao_usuario = db["userActivityLog"].find_one(
            {"user_id": user_id, "question_id": questao_id}
        )
        ultima_revisao = questao_usuario["datas"]["ultima_revisao"].strftime("%d/%m/%Y %H:%M:%S") if questao_usuario else "N/A"
        logger.debug(f"Última revisão obtida para a questão {questao_id} do usuário {user_id}: {ultima_revisao}")
        return ultima_revisao
    except Exception as e:
        logger.error(f"Erro ao obter última revisão para a questão {questao_id} do usuário {user_id}: {e}")
        return "N/A"

def util_get_answer_count(user_id, questao_id):
    try:
        questao_usuario = db["userActivityLog"].find_one(
            {"user_id": user_id, "question_id": questao_id}
        )
        numero_respostas = len(questao_usuario["pastSessions"]) if questao_usuario else 0
        logger.debug(f"Número de respostas obtido para a questão {questao_id} do usuário {user_id}: {numero_respostas}")
        return numero_respostas
    except Exception as e:
        logger.error(f"Erro ao obter número de respostas para a questão {questao_id} do usuário {user_id}: {e}")
        return 0

@bot.message_handler(func=lambda message: message.text.isdigit() and user_quiz_state.get(message.chat.id, {}).get("esperando_resposta", False))
def handle_answer_verification(message):
    user_id = message.chat.id
    logger.debug(f"Usuário {user_id} enviou uma resposta: {message.text}")

    try:
        if not util_check_preconditions(message):
            logger.debug(f"Precondições não atendidas para o usuário {user_id}")
            return

        indice_escolha_usuario, questao_atual, mapeamento_indices = util_get_answer_data(message)
        if indice_escolha_usuario is None:
            util_send_message_with_retry(user_id, "Escolha uma resposta válida.")
            logger.debug(f"Usuário {user_id} escolheu uma resposta inválida: {message.text}")
            return

        # --- Alteração aqui ---
        # Armazenar a resposta do usuário temporariamente
        user_quiz_state[user_id]["resposta_escolhida"] = indice_escolha_usuario 

        # Perguntar sobre o nível de confiança
        markup = util_create_markup(CONFIDENCE_LEVELS, row_width=2)
        util_send_message_with_retry(user_id, "Qual seu nível de confiança nesta resposta?", reply_markup=markup)

        # Mudar o estado para aguardar a resposta de confiança
        user_quiz_state[user_id]["esperando_confianca"] = True
        user_quiz_state[user_id]["esperando_resposta"] = False

        # --- Fim da alteração ---

    except Exception as e:
        util_send_message_with_retry(user_id, "Ocorreu um erro ao processar sua resposta. Por favor, tente novamente.")
        user_quiz_state[user_id]["esperando_resposta"] = False
        logger.error(f"Erro ao processar a resposta do usuário {user_id}: {e}")

# Nova função para lidar com a resposta de confiança
@bot.message_handler(func=lambda message: message.text in CONFIDENCE_LEVELS and user_quiz_state.get(message.chat.id, {}).get("esperando_confianca", False))
def handle_confidence_level(message):
    user_id = message.chat.id
    logger.debug(f"Usuário {user_id} enviou o nível de confiança: {message.text}")

    try:
        # Validar e obter o nível de confiança
        if message.text not in CONFIDENCE_LEVEL_VALUES:
            raise ValueError(f"Nível de confiança inválido: {message.text}")
        nivel_confianca = CONFIDENCE_LEVEL_VALUES[message.text]
        user_quiz_state[user_id]["nivel_confianca"] = nivel_confianca
        logger.debug(f"Nível de confiança definido para o usuário {user_id}: {nivel_confianca}")

        # Obter informações da questão
        indice_escolha_usuario = user_quiz_state[user_id].get("resposta_escolhida")
        questao_atual = user_quiz_state[user_id].get("questao_atual")
        mapeamento_indices = user_quiz_state[user_id].get("respostas_embaralhadas")

        if not all([indice_escolha_usuario is not None, questao_atual, mapeamento_indices]):
            raise ValueError("Informações da questão incompletas")

        # Verificar a resposta
        correta, questao_atualizada = util_verify_answer_logic(questao_atual, indice_escolha_usuario, mapeamento_indices, message)
        
        # Atualizar o estado do quiz
        user_quiz_state[user_id]["respondidas"] += 1
        if correta:
            user_quiz_state[user_id]["acertos"] += 1

        # Calcular o percentual de acertos
        percentual_acertos = util_calculate_accuracy_percentage(user_id)
        logger.debug(f"Percentual de acertos calculado para o usuário {user_id}: {percentual_acertos}%")

        # Preparar e enviar a resposta
        resposta_msg = util_format_answer_message(correta, questao_atualizada)
        util_attempt_send_response(message, resposta_msg)

        # Calcular o tempo gasto
        tempo_gasto = util_calculate_time_spent(user_id)
        
        # Atualizar o histórico do usuário
        util_update_user_history(user_id, questao_atual, correta, indice_escolha_usuario, mapeamento_indices, tempo_gasto, nivel_confianca) # Removido: percentual_acertos
        util_update_user_state(user_id, correta, questao_atualizada)

        # Atualizar a última questão respondida
        global last_answered_question
        last_answered_question[user_id] = questao_atualizada

        # Atualizar o estado do usuário
        user_quiz_state[user_id].update({
            "esperando_confianca": False,
            "esperando_resposta": True
        })

        # Exibir os botões de controle
        util_display_control_buttons(user_id)

        logger.info(f"Processamento da resposta concluído com sucesso para o usuário {user_id}")

    except ValueError as ve:
        logger.warning(f"Erro de validação para o usuário {user_id}: {ve}")
        util_send_message_with_retry(user_id, f"Entrada inválida: {ve}. Por favor, tente novamente.")
    except Exception as e:
        logger.error(f"Erro ao processar a resposta de confiança do usuário {user_id}: {e}", exc_info=True)
        util_send_message_with_retry(user_id, "Ocorreu um erro ao processar sua resposta. Por favor, tente novamente.")
    finally:
        # Garantir que o estado seja resetado em caso de erro
        user_quiz_state[user_id]["esperando_resposta"] = False
        user_quiz_state[user_id]["esperando_confianca"] = False

def util_get_answer_data(message):
    user_id = message.chat.id
    try:
        indice_escolha_usuario = int(message.text) - 1
        logger.debug(f"Índice de escolha do usuário {user_id}: {indice_escolha_usuario}")
    except ValueError:
        logger.warning(f"Valor inválido recebido do usuário {user_id}: {message.text}")
        return None, None, None

    questao_atual = user_quiz_state[user_id].get("questao_atual")
    if questao_atual is None:
        logger.warning(f"Nenhuma questão atual encontrada para o usuário {user_id}")
        return None, None, None

    mapeamento_indices = user_quiz_state[user_id].get("respostas_embaralhadas")
    if mapeamento_indices is None:
        logger.warning(f"Nenhum mapeamento de respostas encontrado para o usuário {user_id}")
        return None, None, None

    logger.debug(f"Dados da resposta obtidos para o usuário {user_id}: questão atual {questao_atual['_id']}, mapeamento {mapeamento_indices}")
    return indice_escolha_usuario, questao_atual, mapeamento_indices

def util_verify_answer_logic(questao_atual, indice_escolha_usuario, mapeamento_indices, message):
    if mapeamento_indices:
        indice_real = mapeamento_indices[indice_escolha_usuario]
    else:
        indice_real = indice_escolha_usuario

    correta = indice_real in questao_atual["correct_answer_indices"]
    logger.debug(f"Verificação da resposta lógica: usuário {message.chat.id}, correta: {correta}")

    if "explanation" not in questao_atual or not questao_atual["explanation"]:
        questao_atual = fetch_and_update_explanation(questao_atual)
        logger.debug(f"Explicação atualizada para a questão {questao_atual['_id']} do usuário {message.chat.id}")
    return correta, questao_atual

def util_clean_markdown(text):
    """
    Remove formatações Markdown de um texto.

    Esta função remove elementos de formatação Markdown, como negrito, itálico, 
    código em linha, e links de um texto, retornando o texto "limpo" dessas formatações.

    Args:
        text (str): O texto a ser limpo.

    Returns:
        str: O texto sem formatações Markdown.
    """
    text = re.sub(r"\*\*|__|\*\*|__", "", text)
    text = re.sub(r"\*|_", "", text)
    text = re.sub(r"`", "", text)
    text = re.sub(r"\[|\]", "", text)
    text = re.sub(r"\(|\)", "", text)
    text = re.sub(r"#", "", text)
    return text

def util_format_answer_message(correta, questao_atual):
    resposta_msg = "\n\n✅ *Resposta Correta! Parabéns!*\n\n" if correta else "\n\n❌ *Resposta Incorreta!*\n\n"
    resposta_msg += "-----------------------------------------------------------------------------\n"
    resposta_msg += f"*A Resposta Correta é:* {questao_atual['answers'][questao_atual['correct_answer_indices'][0]]}\n"
    resposta_msg += "-----------------------------------------------------------------------------\n\n"
    cleaned_explanation = util_clean_markdown(questao_atual.get("explanation", "Explicação não disponível."))
    resposta_msg += f"*Explicação:*\n\n{cleaned_explanation}"
    logger.debug(f"Mensagem de resposta formatada: correta: {correta}, questão {questao_atual['_id']}")
    return resposta_msg

def util_split_message(mensagem, limite_caracteres=4096):
    """Divide uma mensagem em partes menores que o limite de caracteres do Telegram."""
    partes = []
    while len(mensagem) > limite_caracteres:
        # Encontra o último espaço em branco antes do limite de caracteres
        ultimo_espaco = mensagem.rfind(' ', 0, limite_caracteres)
        if ultimo_espaco == -1:
            # Se não houver espaço em branco, divide no limite
            ultimo_espaco = limite_caracteres
        parte = mensagem[:ultimo_espaco]
        partes.append(parte)
        mensagem = mensagem[ultimo_espaco:].strip()
    partes.append(mensagem)
    return partes

def util_attempt_send_response(message, resposta_msg):
    partes_mensagem = util_split_message(resposta_msg)
    num_tentativas = 3
    tempo_espera = 10
    
    for parte in partes_mensagem:
        for tentativa in range(num_tentativas):
            try:
                util_send_message_with_retry(message.chat.id, parte, parse_mode="Markdown")
                logger.debug(f"Parte da resposta enviada para o usuário {message.chat.id} na tentativa {tentativa + 1}")
                break  # Sai do loop de tentativas se o envio for bem-sucedido
            except (requests.exceptions.ConnectTimeout, apihelper.ApiException) as e:
                logger.warning(f"Erro de conexão ao enviar parte da resposta para o usuário {message.chat.id}: tentativa {tentativa + 1}. Erro: {e}")
                if tentativa < num_tentativas - 1:
                    time.sleep(tempo_espera)
        else:
            # Este bloco é executado se o loop de tentativas não for interrompido por um break
            logger.error(f"Falha ao enviar parte da resposta para o usuário {message.chat.id} após {num_tentativas} tentativas")

def util_update_user_history(user_id, questao_atual, correta, indice_escolha_usuario, mapeamento_indices, tempo_gasto, nivel_confianca):
    logger.debug(f"Atualizando histórico do usuário {user_id}. Estado do quiz: {user_quiz_state[user_id]}")
    alternativa_escolhida = questao_atual["answers"][mapeamento_indices[indice_escolha_usuario]]

    modo_sessao = user_quiz_state[user_id].get("modo_quiz", "Desconhecido")
    modo_prova = None
    concurso = None

    # Lógica para determinar modo_prova e concurso
    if modo_sessao == "The Most Badass Geral":
        modo_prova = None
    elif modo_sessao == "Random Geral":
        modo_prova = None
    elif modo_sessao == "Single" or user_quiz_state[user_id].get("single_mode"):
        modo_sessao = "Single"
        modo_prova = None
        concurso = None
    elif modo_sessao == "Concurso Específico":
        concurso = user_quiz_state[user_id].get("concurso", "Desconhecido")
        if user_quiz_state[user_id].get("simulado_mode"):
            modo_prova = "Simulado"
        elif user_quiz_state[user_id].get("shuffle_completo_mode"):
            modo_prova = "Shuffle"
        elif user_quiz_state[user_id].get("badass_geral_mode") or user_quiz_state[user_id].get("badass_mode"):
            modo_prova = "Most Badass"
        else:
            modo_prova = user_quiz_state[user_id].get("prova", "Desconhecido")

    logger.debug(f"Modo de sessão: {modo_sessao}, Modo de prova: {modo_prova}, Concurso: {concurso}")

    update_user_question_performance(
        db,
        user_id,
        questao_atual["_id"],
        correta,
        questao_atual["disciplina"],
        alternativa_escolhida,
        tempo_gasto,
        modo_sessao,
        modo_prova,
        concurso,
        nivel_confianca,
    )

    percentual_acertos = util_calculate_accuracy_percentage(user_id) # Obter percentual_acertos aqui
    logger.debug(f"Histórico atualizado para o usuário {user_id}: questão {questao_atual['_id']}, correta: {correta}, concurso: {concurso}, modo_sessao: {modo_sessao}, modo_prova: {modo_prova}")
    
    return percentual_acertos # Retornar percentual_acertos

def util_update_user_state(user_id, correta, questao_atualizada):
    if user_id in user_quiz_state:
        if correta:
            user_quiz_state[user_id]["acertos"] += 1
        user_quiz_state[user_id]["last_question"] = questao_atualizada
        global last_question
        last_question = questao_atualizada
        logger.debug(f"Estado do usuário {user_id} atualizado: questão {questao_atualizada['_id']}, correta: {correta}")

def util_check_preconditions(message):
    user_id = message.chat.id
    if user_id in user_quiz_state and user_quiz_state[user_id].get("reportando_problema"):
        logger.debug(f"Precondições não atendidas: usuário {user_id} está reportando um problema")
        return False
    if user_id not in user_quiz_state or "questao_atual" not in user_quiz_state[user_id]:
        bot.reply_to(message, "Inicie o quiz primeiro usando o comando /start.")
        logger.debug(f"Precondições não atendidas: usuário {user_id} não iniciou um quiz")
        return False
    logger.debug(f"Precondições atendidas para o usuário {user_id}")
    return True

def handle_send_question(message):
    user_id = message.chat.id
    logger.debug(f"Usuário {user_id} solicitou envio de questão")

    if user_id not in user_quiz_state:
        logger.warning(f"Usuário {user_id} não está em um quiz ativo")
        util_send_message_with_retry(user_id, "Por favor, inicie um quiz primeiro usando o comando /start.")
        return

    if user_quiz_state[user_id].get("esperando_resposta", False):
        logger.debug(f"Usuário {user_id} está aguardando uma resposta. Envio de nova questão cancelado.")
        return

    try:
        if user_quiz_state[user_id].get("simulado_mode"):
            questao = util_get_simulation_question(user_id)
        else:
            questao = util_get_regular_question(user_id)

        if not questao:
            logger.debug(f"Não há questões disponíveis para o usuário {user_id}")
            util_send_message_with_retry(user_id, "Desculpe, não há questões disponíveis. Por favor, tente novamente mais tarde.")
            handle_quiz_completion(user_id)
            return

        # Prepara e envia a questão formatada para o usuário
        logger.debug(f"Preparando questão {questao['_id']} para envio")
        util_prepare_question(user_id, questao)
        util_send_formatted_question(user_id, questao)
        user_quiz_state[user_id]["esperando_resposta"] = True

        # Verifica o modo do quiz antes de incrementar 'questoes_enviadas'
        if not user_quiz_state[user_id].get("single_mode"):
            user_quiz_state[user_id]["questoes_enviadas"] += 1
            logger.debug(f"Questão {questao['_id']} enviada para o usuário {user_id}")

    except Exception as e:
        logger.error(f"Erro ao enviar questão para o usuário {user_id}: {e}", exc_info=True)
        util_send_message_with_retry(user_id, "Ocorreu um erro ao enviar a questão. Por favor, tente novamente mais tarde.")

def util_get_simulation_question(user_id):
    questoes_simulado = user_quiz_state[user_id].get("questoes_simulado", [])
    indice_atual = user_quiz_state[user_id].get("indice_atual", 0)
    logger.debug(f"Modo simulado ativo para o usuário {user_id}. Questões simuladas: {len(questoes_simulado)}, índice atual: {indice_atual}")

    if indice_atual < len(questoes_simulado):
        questao = questoes_simulado[indice_atual]
        user_quiz_state[user_id]["indice_atual"] += 1
        logger.debug(f"Questão {questao['_id']} selecionada para o usuário {user_id} no índice {indice_atual}")
        return questao
    else:
        logger.debug(f"Todas as questões do simulado foram respondidas pelo usuário {user_id}.")
        util_send_message_with_retry(user_id, "Todas as questões do simulado foram respondidas.")
        return None

def send_next_question(user_id):
    """Envia a próxima questão do quiz para o usuário."""

    logger.debug(f"=== Iniciando send_next_question para o usuário {user_id} ===")

    if user_id not in user_quiz_state or user_quiz_state[user_id].get("quiz_finalizado"):
        logger.debug(f"Usuário {user_id} não está em user_quiz_state ou quiz_finalizado é True.")
        handle_quiz_completion(user_id)
        return

    if user_quiz_state[user_id].get("esperando_resposta", False):
        logger.debug(f"Usuário {user_id} está aguardando uma resposta. Envio de próxima questão cancelado.")
        return

    questoes_respondidas = user_quiz_state[user_id].get("respondidas", 0)
    qtDefinedAnswer = user_quiz_state[user_id].get("quantidade", 1)  # Default para 1 no modo Single
    logger.debug(f"Usuário {user_id} respondeu {questoes_respondidas} de {qtDefinedAnswer} questões")

    questao = None

    try:
        if user_quiz_state[user_id].get("single_mode"):
            if "fila_de_questoes" in user_quiz_state[user_id] and user_quiz_state[user_id]["fila_de_questoes"]:
                questao = user_quiz_state[user_id]["fila_de_questoes"].pop(0)
                logger.debug(f"Questão selecionada para o modo Single: {questao['_id']}")
            else:
                logger.debug(f"Nenhuma questão encontrada para o modo Single do usuário {user_id}. Finalizando o quiz.")
                handle_quiz_completion(user_id)
                return
        elif user_quiz_state[user_id].get("simulado_mode"):
            questao = util_get_simulation_question(user_id)
            logger.debug(f"Questão selecionada para o modo Simulado: {questao['_id'] if questao else 'Nenhuma'}")
        elif user_quiz_state[user_id].get("shuffle_completo_mode"):
            questao = util_get_random_question(user_id)
            logger.debug(f"Questão selecionada para o modo Random Geral: {questao['_id'] if questao else 'Nenhuma'}")
            user_quiz_state[user_id]['concurso'] = 'Geral'  # Definindo concurso como 'Geral' para Random Geral
        elif user_quiz_state[user_id].get("badass_geral_mode"):
            questoes_badass = fetch_badass_questions(user_id, 1, modo_geral=True)
            questao = questoes_badass[0] if questoes_badass else None
            logger.debug(f"Questão selecionada para o modo The Most Badass Geral: {questao['_id'] if questao else 'Nenhuma'}")
            user_quiz_state[user_id]['concurso'] = 'Geral'  # Definindo concurso como 'Geral' para The Most Badass Geral
        elif user_quiz_state[user_id].get("badass_mode"):
            questoes_badass = fetch_badass_questions(user_id, 1, modo_geral=False)
            questao = questoes_badass[0] if questoes_badass else None
            logger.debug(f"Questão selecionada para o modo The Most Badass: {questao['_id'] if questao else 'Nenhuma'}")
        else:  # Modo Shuffle ou quiz normal
            questao = util_get_regular_question(user_id)
            logger.debug(f"Questão selecionada para o modo normal/shuffle: {questao['_id'] if questao else 'Nenhuma'}")

        if questao:
            # Prepara e envia a questão selecionada
            logger.debug(f"Preparando questão {questao['_id']} para envio")
            util_prepare_question(user_id, questao)
            util_send_formatted_question(user_id, questao)
            user_quiz_state[user_id]["esperando_resposta"] = True

            # Incrementa 'questoes_enviadas' apenas se não for modo Single
            if not user_quiz_state[user_id].get("single_mode"):
                user_quiz_state[user_id]["questoes_enviadas"] = user_quiz_state[user_id].get("questoes_enviadas", 0) + 1
                logger.debug(f"Questão {questao['_id']} enviada para o usuário {user_id}")

            # Verifica se o quiz foi finalizado após o envio da questão
            util_quiz_completion(user_id)
        else:
            logger.debug(f"Nenhuma questão encontrada para enviar ao usuário {user_id}. Finalizando o quiz.")
            handle_quiz_completion(user_id)

    except Exception as e:
        logger.error(f"Erro ao enviar próxima questão para o usuário {user_id}: {e}", exc_info=True)
        util_send_message_with_retry(user_id, "Ocorreu um erro ao enviar a próxima questão. Por favor, tente novamente mais tarde.")

    logger.debug(f"=== Fim de send_next_question para o usuário {user_id} ===")

@bot.message_handler(func=lambda message: message.text == "Próxima Questão" or message.text == "Finalizar Quiz")
def handle_proxima_questao(message):
    user_id = message.chat.id

    try:
        if user_id in user_quiz_state:
            if message.text == "Finalizar Quiz":
                handle_quiz_completion(user_id)
            else:
                send_next_question(user_id)
        else:
            util_send_message_with_retry(user_id, "Você precisa iniciar um quiz primeiro.")

    except Exception as e:
        logger.error(f"Erro ao processar a solicitação de próxima questão para o usuário {user_id}: {e}", exc_info=True)
        util_send_message_with_retry(user_id, "Ocorreu um erro. Por favor, tente novamente mais tarde.")

def util_get_regular_question(user_id):
    concurso = user_quiz_state[user_id].get("concurso")
    prova = user_quiz_state[user_id].get("prova")
    disciplina = user_quiz_state[user_id].get("disciplina")
    fonte = user_quiz_state[user_id].get("fonte")
    quantidade = user_quiz_state[user_id]['quantidade']

    if user_quiz_state[user_id].get("badass_mode"):
        logger.debug(f"Modo 'Most Badass' ativo para o usuário {user_id}")
        questoes = fetch_badass_questions(user_id, quantidade)
    else:  # Modo Shuffle
        logger.debug(f"Modo 'Shuffle' ativo para o usuário {user_id}")
        logger.debug(f"Buscando {quantidade} questões para o usuário {user_id}. Concurso: {concurso}, Prova: {prova}, Disciplina: {disciplina}, Fonte: {fonte}")
        questoes = fetch_quiz_questions(user_id, quantidade, concurso, prova, disciplina, fonte)

    user_quiz_state[user_id]['fila_de_questoes'] = questoes

    while not user_quiz_state[user_id].get("quiz_finalizado"):
        if user_quiz_state[user_id]["fila_de_questoes"]:
            return user_quiz_state[user_id]["fila_de_questoes"].pop(0)
        else:
            logger.debug(f"Não há mais questões na fila para o usuário {user_id}. Buscando uma nova questão...")
            nova_questao = fetch_questions(user_id, concurso, prova, disciplina, fonte)
            if nova_questao:
                return nova_questao
            else:
                logger.warning(f"Não foi possível encontrar uma nova questão para o usuário {user_id}.")
                return None

def util_check_question_reported(user_id, questao_id):
    logger.debug(f"Verificando se a questão {questao_id} foi reportada pelo usuário {user_id}")
    reportadas = db["reportedQuestoes"].find_one({"questao_id": questao_id, "user_id": user_id})
    foi_reportada = reportadas is not None
    logger.debug(f"Questão {questao_id} foi reportada pelo usuário {user_id}: {foi_reportada}")
    return foi_reportada

def handle_immediate_question_send(user_id):
    logger.debug(f"Enviando questão imediatamente para o usuário {user_id}")

    if user_quiz_state[user_id].get("esperando_resposta", False):
        logger.debug(f"Usuário {user_id} está aguardando uma resposta. Envio imediato de questão cancelado.")
        return

    chat = types.Chat(id=user_id, type='private')
    from_user = types.User(id=user_id, is_bot=False, first_name="Usuário")

    message = types.Message(
        message_id=0,
        from_user=from_user,
        date=datetime.now(ZoneInfo("America/Sao_Paulo")),
        chat=chat,
        content_type='text',
        options={},
        json_string='{}'
    )

    handle_send_question(message)
    user_quiz_state[user_id]["esperando_resposta"] = True
    logger.debug(f"Questão enviada imediatamente para o usuário {user_id}")

def util_calculate_time_spent(user_id):
    tempo_inicio = question_start_time.get(user_id, time.time())
    tempo_gasto = int(time.time() - tempo_inicio)
    logger.debug(f"Tempo gasto calculado para o usuário {user_id}: {tempo_gasto} segundos")
    return tempo_gasto

def util_quiz_completion(user_id):
    """Verifica se o quiz foi completado e, em caso afirmativo, finaliza-o."""
    logger.debug(f"Verificando conclusão do quiz para o usuário {user_id}")

    logger.debug(f"Verificando se o quiz do usuário {user_id} foi completado. "
                 f"Respondidas: {user_quiz_state[user_id]['respondidas']}, "
                 f"Quantidade: {user_quiz_state[user_id]['quantidade']}") 

    if user_quiz_state[user_id]["respondidas"] == user_quiz_state[user_id]["quantidade"]:
        logger.debug(f"O quiz do usuário {user_id} foi completado. Chamando handle_quiz_completion.")

        handle_quiz_completion(user_id)
        logger.debug(f"Quiz finalizado para o usuário {user_id}")

def handle_quiz_completion(user_id):
    logger.debug(f"Iniciando finalização do quiz para o usuário {user_id}")
    try:
        estado_quiz = user_quiz_state.get(user_id, {})
        respondidas = estado_quiz.get("respondidas", 0)
        acertos = estado_quiz.get("acertos", 0)
        questoes_reportadas = estado_quiz.get("questoes_reportadas", 0)
        qtDefinedAnswer = estado_quiz.get("quantidade", 0)
        questoes_enviadas = estado_quiz.get("questoes_enviadas", 0)

        taxa_acertos_sessao = (acertos / respondidas) * 100 if respondidas > 0 else 0
        logger.debug(
            f"Taxa de acertos da sessão calculada para o usuário {user_id}: "
            f"{taxa_acertos_sessao:.2f}%"
        )

        br_tz = ZoneInfo("America/Sao_Paulo")
        data_atual = datetime.now(br_tz)
        inicio_dia = data_atual.replace(hour=0, minute=0, second=0, microsecond=0)
        fim_dia = data_atual.replace(
            hour=23, minute=59, second=59, microsecond=999999
        )

        # Executar o pipeline no Compass e obter o resultado
        pipeline = [
            {
                '$unwind': '$pastSessions'
            },
            {
                '$sort': {
                    'pastSessions.timestamp': 1
                }
            },
            {
                '$group': {
                    '_id': {
                        'questionId': '$pastSessions.questionId',
                        'disciplina': '$disciplina'
                    },
                    'sessionAttempts': {
                        '$push': {
                            'status': '$pastSessions.status',
                            'timestamp': '$pastSessions.timestamp'
                        }
                    }
                }
            },
            {
                '$project': {
                    'disciplina': '$_id.disciplina',
                    'score': {
                        '$reduce': {
                            'input': '$sessionAttempts',
                            'initialValue': {
                                'score': 0,
                                'streak': 0
                            },
                            'in': {
                                'score': {
                                    '$cond': [
                                        '$$this.status',
                                        {
                                            '$add': [
                                                '$$value.score',
                                                {
                                                    '$switch': {
                                                        'branches': [
                                                            {
                                                                'case': {
                                                                    '$eq': [
                                                                        '$$value.streak',
                                                                        0
                                                                    ]
                                                                },
                                                                'then': 0.5
                                                            },
                                                            {
                                                                'case': {
                                                                    '$eq': [
                                                                        '$$value.streak',
                                                                        1
                                                                    ]
                                                                },
                                                                'then': 0.3
                                                            },
                                                            {
                                                                'case': {
                                                                    '$gte': [
                                                                        '$$value.streak',
                                                                        2
                                                                    ]
                                                                },
                                                                'then': 0.2
                                                            }
                                                        ],
                                                        'default': 0
                                                    }
                                                }
                                            ]
                                        },
                                        {
                                            '$max': [
                                                {
                                                    '$subtract': [
                                                        '$$value.score',
                                                        0.1
                                                    ]
                                                },
                                                0
                                            ]
                                        }
                                    ]
                                },
                                'streak': {
                                    '$cond': [
                                        '$$this.status',
                                        {
                                            '$add': ['$$value.streak', 1]
                                        },
                                        0
                                    ]
                                }
                            }
                        }
                    },
                    'attempts': {
                        '$size': '$sessionAttempts'
                    }
                }
            },
            {
                '$group': {
                    '_id': '$disciplina',
                    'totalPoints': {
                        '$sum': '$score.score'
                    },
                    'attempts': {
                        '$sum': '$attempts'
                    }
                }
            },
            {
                '$lookup': {
                    'from': 'todasQuestoes',
                    'let': {
                        'disciplina': '$_id'
                    },
                    'pipeline': [
                        {
                            '$match': {
                                '$expr': {
                                    '$eq': ['$disciplina', '$$disciplina']
                                }
                            }
                        },
                        {
                            '$count': 'total'
                        }
                    ],
                    'as': 'totalQuestions'
                }
            },
            {
                '$unwind': '$totalQuestions'
            },
            {
                '$project': {
                    'disciplina': '$_id',
                    'progressPercentage': {
                        '$multiply': [
                            {
                                '$divide': [
                                    '$totalPoints',
                                    '$totalQuestions.total'
                                ]
                            },
                            100
                        ]
                    },
                    'attempts': 1,
                    'totalQuestions': '$totalQuestions.total',
                    'totalPoints': 1,
                    'requiredPoints': {
                        '$multiply': ['$totalQuestions.total', 1]
                    }
                }
            },
            {
                '$group': {
                    '_id': None,
                    'progressDisciplinas': {
                        '$push': '$$ROOT'
                    },
                    'totalAttempts': {
                        '$sum': '$attempts'
                    },
                    'totalPoints': {
                        '$sum': '$totalPoints'
                    },
                    'totalRequiredPoints': {
                        '$sum': '$requiredPoints'
                    }
                }
            },
            {
                '$project': {
                    '_id': 0,
                    'progressDisciplinas': 1,
                    'progressGlobal': {
                        'progressPercentage': {
                            '$multiply': [
                                {
                                    '$divide': [
                                        '$totalPoints',
                                        '$totalRequiredPoints'
                                    ]
                                },
                                100
                            ]
                        },
                        'attempts': '$totalAttempts',
                        'totalQuestions': '$totalRequiredPoints',
                        'totalPoints': '$totalPoints',
                        'requiredPoints': '$totalRequiredPoints'
                    }
                }
            }
        ]

        cursor = db.command('aggregate', 'userActivityLog', pipeline=pipeline, cursor={}, allowDiskUse=True)
        resultado = cursor['cursor']['firstBatch']

        logger.debug(f"Resultado da agregação para o usuário {user_id}: {resultado}")

        if not resultado:
            logger.warning(f"Nenhum resultado encontrado na agregação para o usuário {user_id}")
            progress_percentage = 0
        else:
            progress_percentage = min(resultado[0]['progressGlobal']['progressPercentage'], 100)

        logger.debug(f"Progress percentage para o usuário {user_id}: {progress_percentage}")

        resultados = db["userActivityLog"].aggregate(
            [
                {"$match": {"user_id": user_id}},
                {"$unwind": "$pastSessions"},
                {
                    "$group": {
                        "_id": None,
                        "total_acertos_geral": {
                            "$sum": {
                                "$cond": [
                                    {"$eq": ["$pastSessions.status", True]},
                                    1,
                                    0,
                                ]
                            }
                        },
                        "total_questoes_geral": {"$sum": 1},
                        "total_acertos_dia": {
                            "$sum": {
                                "$cond": [
                                    {
                                        "$and": [
                                            {
                                                "$eq": [
                                                    "$pastSessions.status",
                                                    True,
                                                ]
                                            },
                                            {
                                                "$gte": [
                                                    "$pastSessions.data",
                                                    inicio_dia,
                                                ]
                                            },
                                            {
                                                "$lt": [
                                                    "$pastSessions.data",
                                                    fim_dia,
                                                ]
                                            },
                                        ]
                                    },
                                    1,
                                    0,
                                ]
                            }
                        },
                        "total_questoes_dia": {
                            "$sum": {
                                "$cond": [
                                    {
                                        "$and": [
                                            {
                                                "$gte": [
                                                    "$pastSessions.data",
                                                    inicio_dia,
                                                ]
                                            },
                                            {
                                                "$lt": [
                                                    "$pastSessions.data",
                                                    fim_dia,
                                                ]
                                            },
                                        ]
                                    },
                                    1,
                                    0,
                                ]
                            }
                        },
                    }
                },
            ]
        )

        resultados = list(resultados)
        logger.debug(f"Resultados da agregação de atividade para o usuário {user_id}: {resultados}")

        if resultados:
            total_acertos_geral = resultados[0]["total_acertos_geral"]
            total_questoes_geral = resultados[0]["total_questoes_geral"]
            total_acertos_dia = resultados[0]["total_acertos_dia"]
            total_questoes_dia = resultados[0]["total_questoes_dia"]

            taxa_acertos_geral = min((total_acertos_geral / total_questoes_geral) * 100 if total_questoes_geral > 0 else 0, 100)
            taxa_acertos_dia = min((total_acertos_dia / total_questoes_dia) * 100 if total_questoes_dia > 0 else 0, 100)
            logger.debug(
                f"Estatísticas do usuário {user_id}: Total acertos geral: {total_acertos_geral}, "
                f"Total questões geral: {total_questoes_geral}, Total acertos dia: {total_acertos_dia}, "
                f"Total questões dia: {total_questoes_dia}"
            )
        else:
            taxa_acertos_geral = 0
            taxa_acertos_dia = 0
            total_acertos_dia = 0
            total_questoes_dia = 0
            logger.debug(f"Nenhum resultado encontrado para o usuário {user_id}")

        mensagem_final = (
            f"*Quiz Finalizado! 🎉*\n\n"
            f"Taxa de Acertos Geral: {taxa_acertos_geral:.2f}%\n"
            f"Taxa de Acertos Dia: {taxa_acertos_dia:.2f}%\n"
            f"Taxa de Acertos Sessão: {min(taxa_acertos_sessao, 100):.2f}%\n\n"
            f"Questões Praticadas Dia: {total_questoes_dia}\n"
            f"Total de Acertos no dia: {total_acertos_dia} de {QUESTIONS_DAY_TARGET}\n\n"
            f"**Progresso Geral: {progress_percentage:.2f}%**\n\n"
            f"_Parabéns pelo seu Esforço! Para uma Melhor Retenção, faça a Revisão após 15 minutos._"
        )
        util_send_message_with_retry(user_id, mensagem_final, parse_mode="Markdown")

        global last_answered_question

        # Desabilitar teclado após finalizar o quiz
        markup = types.ReplyKeyboardRemove(selective=False)  # Cria um teclado vazio
        util_send_message_with_retry(user_id, "Use o comando /start para iniciar um novo quiz.", reply_markup=markup)

        last_answered_question[user_id] = estado_quiz.get("questao_atual")

        user_quiz_state[user_id] = {
            "last_question": last_answered_question[user_id],
            "quiz_finalizado": True,
        }

        logger.debug(f"Quiz finalizado com sucesso para o usuário {user_id}")

    except Exception as e:
        util_send_message_with_retry(
            user_id,
            "Ocorreu um erro ao finalizar o quiz. Por favor, tente novamente mais tarde.",
        )
        logger.error(f"Erro ao finalizar o quiz para o usuário {user_id}: {e}", exc_info=True)

    finally:
        if user_id in user_quiz_state:
            del user_quiz_state[user_id]
            logger.debug(
                f"Dados do quiz do usuário {user_id} foram removidos após finalização"
            )

if __name__ == "__main__":
    try:
        logger.debug("Iniciando o bot")
        bot.polling(none_stop=True, interval=0, timeout=20)  # Iniciar o bot

        # Agendar a função de verificação diária APÓS o bot.polling()
        schedule.every().day.at("00:00").do(util_manage_reviews_and_decay)

        while True:
            schedule.run_pending()
            time.sleep(1)
    except Exception as e:
        logger.error(f"Erro fatal: {e}", exc_info=True)
