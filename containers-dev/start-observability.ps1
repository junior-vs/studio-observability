# Scripts para gerenciar a stack de observabilidade

# Iniciar todos os serviços
Write-Host "🚀 Iniciando stack de observabilidade..." -ForegroundColor Cyan
docker-compose up -d

Write-Host ""
Write-Host "⏳ Aguardando serviços iniciarem..." -ForegroundColor Yellow
Start-Sleep -Seconds 10

Write-Host ""
Write-Host "✅ Serviços disponíveis:" -ForegroundColor Green
Write-Host "  📊 Grafana:     http://localhost:3000 (admin/admin)" -ForegroundColor White
Write-Host "  📈 Prometheus:  http://localhost:9090" -ForegroundColor White
Write-Host "  🔍 Kibana:      http://localhost:5601" -ForegroundColor White
Write-Host "  🔎 Elasticsearch: http://localhost:9200" -ForegroundColor White
Write-Host "  📝 Graylog:     http://localhost:9000 (admin/admin)" -ForegroundColor White

Write-Host ""
Write-Host "🔧 Para iniciar a aplicação:" -ForegroundColor Cyan
Write-Host "  cd logging-quarkus" -ForegroundColor White
Write-Host "  .\mvnw.cmd quarkus:dev" -ForegroundColor White

Write-Host ""
Write-Host "📖 Documentação completa: OBSERVABILITY.md" -ForegroundColor Cyan
