#!/bin/bash

# Para a stack de observabilidade

echo "🛑 Parando stack de observabilidade..."
docker-compose down

echo ""
echo "✅ Serviços parados!"
echo ""
echo "💡 Para remover também os volumes (dados persistidos):"
echo "   docker-compose down -v"
