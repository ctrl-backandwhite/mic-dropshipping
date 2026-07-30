package com.nexaplatform.dropshipping.infrastructure.integration.storage;

/**
 * Fallo al leer o escribir en el almacenamiento de objetos.
 *
 * <p>El cliente de MinIO declara nueve excepciones comprobadas distintas ({@code ErrorResponseException},
 * {@code InsufficientDataException}, {@code XmlParserException}...). Propagarlas obligaba a firmar
 * {@code throws Exception}, que no dice nada y arrastra a quien llame a capturar cualquier cosa. Quien
 * sube una imagen no puede hacer nada distinto según cuál de las nueve sea: o funcionó, o no. Aquí se
 * unifican en una sola, conservando la original como causa para el diagnóstico.
 */
public class ObjectStorageException extends RuntimeException {

    public ObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
