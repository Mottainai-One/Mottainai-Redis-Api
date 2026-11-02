# Destino de promoção na caixa Redis

A publicação existente em POST /internal/v1/notifications aceita os campos opcionais promotionId, storeId, productId e expiresAt. Os quatro devem aparecer juntos; os IDs são inteiros SQL positivos e expiresAt é um instante futuro em milissegundos UTC. Um destino incompleto, vencido ou inválido retorna 400 antes de escrever no Stream.

Exemplo de contrato para serviços confiáveis, sem representar uma conta real:

```json
{"companyId":1,"userId":4294967308,"id":"customer-promotion-delivery-123","title":"Produto em oferta","body":"Confira esta oferta na loja.","priority":3,"promotionId":10,"storeId":20,"productId":30,"expiresAt":1793664000000}
```

Usar Authorization específico da integração somente para ler a caixa. A escrita interna usa X-Mottainai-Notification-Secret, configurado por NOTIFICATION_PUBLISHER_SECRET, com pelo menos 32 caracteres aleatórios. Não colocar o segredo no aplicativo nem expor diretamente a porta Redis.

O endpoint responde 202 com id e status=queued somente após adicionar o evento ao Stream. O consumidor converte os campos de destino para o hash da notificação. A listagem devolve promotionId, storeId, productId e expiresAt quando presentes. O aplicativo pode usar esses IDs para consultar a oferta real e abrir o produto correto.

createdAt continua em SEGUNDOS UTC, preservando o contrato operacional existente. expiresAt é em MILISSEGUNDOS UTC. Os nomes internos do Stream/hash são promotion_id, store_id, product_id e expires_at; os campos HTTP usam camelCase.

O TTL de uma oferta é o menor valor entre sete dias e o tempo até expiresAt. A reconciliação de leitura usa o relógio Redis para retirar ofertas vencidas do índice e recalcular o contador. Uma oferta que vence antes do consumo é descartada sem gerar aviso. A repetição de um ID ainda existente preserva texto, data, leitura e validade originais; não estende a campanha nem reabre um item lido.

Avisos genéricos de funcionários continuam sem destino de promoção. Não mudam a autenticação, as rotas de listagem/leitura nem a unidade de createdAt. Esta API não decide quem recebe primeiro, o raio ou a quota diária: essa seleção e a fila de tentativas pertencem à API Cliente e ao banco operacional.

## Configuração

Publicar esta versão antes de habilitar CUSTOMER_NOTIFICATION_DISPATCH_ENABLED na API Cliente. Combinar o mesmo NOTIFICATION_PUBLISHER_SECRET entre os servidores e uma URL privada HTTPS. A credencial JWT de leitura é separada do segredo de publicação. O namespace e o destinatário numérico devem corresponder à troca de token da API Cliente.

Usar persistência apropriada do Redis e monitorar Stream, pendências e DLQ. A confirmação 202 significa que o evento foi enfileirado; não confirma que o celular mostrou a notificação. A entrega no Android depende de rede, permissões, localização e agendamento do sistema.

## Testes

Executar mvn --batch-mode verify com REDIS_TEST_HOST apontando para uma instância Redis de testes. A suíte cobre o ingresso autenticado, metadados até a caixa, isolamento entre usuários, leitura, repetição idempotente, TTL, descarte de ofertas vencidas e compatibilidade com avisos genéricos. Não usar uma instância de produção para testes.