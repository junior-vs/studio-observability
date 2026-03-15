# Para a stack de observabilidade

Write-Host "🛑 Parando stack de observabilidade..." -ForegroundColor Yellow
docker-compose down

Write-Host ""
Write-Host "✅ Serviços parados!" -ForegroundColor Green
Write-Host ""
Write-Host "💡 Para remover também os volumes (dados persistidos):" -ForegroundColor Cyan
Write-Host "   docker-compose down -v" -ForegroundColor White
