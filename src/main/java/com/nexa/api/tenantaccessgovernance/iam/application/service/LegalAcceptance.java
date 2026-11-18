package com.nexa.api.tenantaccessgovernance.iam.application.service;

import com.nexa.api.tenantaccessgovernance.iam.application.model.LegalAcceptanceModels.*;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.LegalAcceptanceStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.nexa.api.tenantaccessgovernance.iam.application.exception.LegalDocumentChangedException;
import org.springframework.stereotype.Service;

/** Individual identity acceptance of the current mobile usage document. */
@Service
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class LegalAcceptance {
    public static final String DOCUMENT_ID = "mobile-terms";
    public static final String VERSION = "1";
    // This version and its exact UTF-8 content are immutable. Publish a new version for any text change.
    public static final String CONTENT = """
            Condiciones de uso de Nexa para el proyecto ICISA

            Usa únicamente la cuenta autorizada que te corresponde y protege tus credenciales. No compartas tu acceso. Utiliza Nexa para las actividades operativas autorizadas de tu organización.

            Los pedidos, pagos y recepciones se consideran registrados según la confirmación del servidor. Si una acción queda pendiente sin conexión, reconecta y verifica el resultado en Nexa antes de tratarla como completada; los datos guardados sin conexión pueden estar desactualizados.

            Puedes desactivar notificaciones y consultar la bandeja dentro de Nexa. La cámara solicita permiso cuando se usa; la recepción de entrega permite escribir el código si no utilizas cámara.

            Al terminar en un dispositivo compartido, cierra sesión y protege la información visible en el dispositivo.
            """;
    public static final String DIGEST = digest(CONTENT);
    private final LegalAcceptanceStore store;

    public LegalAcceptance(LegalAcceptanceStore store) { this.store = store; }

    public View current(UUID userId) {
        Optional<Evidence> evidence = store.find(userId, DOCUMENT_ID, VERSION);
        evidence.ifPresent(value -> {
            if (!DIGEST.equals(value.digest())) throw new IllegalStateException("Legal document content changed without a version change");
        });
        return new View(DOCUMENT_ID, VERSION, CONTENT, DIGEST, evidence.isPresent(), evidence.map(Evidence::acceptedAt).orElse(null));
    }

    public View accept(UUID userId, Request request) {
        if (request == null || !DOCUMENT_ID.equals(request.documentId()) || !VERSION.equals(request.version())
                || !DIGEST.equals(request.contentDigest())) {
            throw new LegalDocumentChangedException();
        }
        store.insertIfAbsent(userId, DOCUMENT_ID, VERSION, DIGEST);
        return current(userId);
    }

    private static String digest(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

}
