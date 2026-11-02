package com.mottainai.redis;

import java.util.Map;

/** Identifica uma oferta real sem interpretar o título do aviso como link ou identificador. */
public record PromotionNotificationTarget(long promotionId, long storeId, long productId, long expiresAt) {
    /** Aceita somente IDs SQL positivos e validade expressa em milissegundos UTC. */
    public PromotionNotificationTarget {
        if (promotionId < 1 || promotionId > Integer.MAX_VALUE || storeId < 1 || storeId > Integer.MAX_VALUE
                || productId < 1 || productId > Integer.MAX_VALUE || expiresAt < 100_000_000_000L) {
            throw new IllegalArgumentException("Invalid promotion target");
        }
    }

    /** Mantém compatibilidade com avisos genéricos e rejeita destinos incompletos. */
    public static PromotionNotificationTarget optional(Long promotionId, Long storeId, Long productId, Long expiresAt) {
        if (promotionId == null && storeId == null && productId == null && expiresAt == null) return null;
        if (promotionId == null || storeId == null || productId == null || expiresAt == null) {
            throw new IllegalArgumentException("Incomplete promotion target");
        }
        return new PromotionNotificationTarget(promotionId, storeId, productId, expiresAt);
    }

    /** Lê os mesmos campos opcionais no contrato do Stream. */
    public static PromotionNotificationTarget from(Map<String, String> fields) {
        return optional(number(fields.get("promotion_id")), number(fields.get("store_id")),
                number(fields.get("product_id")), number(fields.get("expires_at")));
    }

    /** Converte apenas campos presentes; erros de formato seguem o tratamento de mensagem inválida. */
    private static Long number(String value) { return value == null ? null : Long.valueOf(value); }

    /** Acrescenta o destino ao evento privado sem substituir os dados de identificação da caixa. */
    public void appendTo(Map<String, String> fields) {
        fields.put("promotion_id", Long.toString(promotionId));
        fields.put("store_id", Long.toString(storeId));
        fields.put("product_id", Long.toString(productId));
        fields.put("expires_at", Long.toString(expiresAt));
    }
}
