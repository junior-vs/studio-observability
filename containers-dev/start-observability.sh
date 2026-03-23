#!/bin/bash

# Scripts para gerenciar a stack de observabilidade

echo "🚀 Iniciando stack de observabilidade..."
docker-compose up -d

echo ""
echo "⏳ Aguardando serviços iniciarem..."
sleep 10

echo ""
echo "✅ Serviços disponíveis:"
echo "  📊 Grafana:        http://localhost:3000 (admin/admin)"
echo "  📈 Prometheus:     http://localhost:9090"
echo "  🔍 Kibana:         http://localhost:5601"
echo "  🔎 Elasticsearch:  http://localhost:9200"
echo "  📝 Graylog:        http://localhost:9000 (admin/admin)"

echo ""
echo "🔧 Para iniciar a aplicação:"
echo "  cd logging-quarkus"
echo "  ./mvnw quarkus:dev"

echo ""
echo "📖 Documentação completa: OBSERVABILITY.md"
