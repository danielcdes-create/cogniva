import logging
from pymongo import MongoClient
from datetime import datetime

# Configurar logging
logging.basicConfig(filename='/var/log/countdown.log', level=logging.INFO)

# Registro de início do script
logging.info(f'Execução iniciada em: {datetime.utcnow()}')

client = MongoClient(os.getenv("MONGODB_URI", "mongodb://localhost:27017"))
db = client.CNU

# Acessar a coleção userActivityLog
collection = db["utils"]

# Obter a data de hoje em formato ISO
today = datetime.utcnow().strftime('%Y-%m-%dT%H:%M:%SZ')

pipeline = [
    {
        '$addFields': {
            'today': {
                '$dateFromString': {
                    'dateString': today
                }
            }
        }
    },
    {
        '$project': {
            '_id': 1,
            'dataCNU': 1,
            'today': 1,
            'countdown': {
                '$divide': [
                    { '$subtract': [ '$dataCNU', '$today' ] },
                    1000 * 60 * 60 * 24
                ]
            }
        }
    },
    {
        '$merge': {
            'into': 'utils',
            'on': '_id',
            'whenMatched': 'merge',
            'whenNotMatched': 'discard'
        }
    }
]

# Executar a agregação
collection.aggregate(pipeline)

# Registro de conclusão do script
logging.info(f'Execução concluída em: {datetime.utcnow()}')
