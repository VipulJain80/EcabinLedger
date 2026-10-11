package com.ecabin.ledger.storage;

import com.azure.core.util.BinaryData;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobHttpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Private Azure Blob/Azurite storage. Production uses DefaultAzureCredential (managed identity/workload identity). */
@Component
@ConditionalOnProperty(name="app.attachments.provider", havingValue="azure", matchIfMissing=true)
public class AzureBlobStorage implements BlobStorage {
    @Value("${app.attachments.azure-endpoint:}") private String endpoint;
    @Value("${app.attachments.azure-container:ecabin-evidence-private}") private String container;
    @Value("${app.attachments.azure-connection-string:}") private String connectionString;

    private BlobContainerClient client() {
        if (connectionString != null && !connectionString.isBlank()) return new BlobServiceClientBuilder().connectionString(connectionString).buildClient().getBlobContainerClient(container);
        if (endpoint == null || endpoint.isBlank()) throw new IllegalStateException("Azure Blob Storage is not configured. Set AZURE_STORAGE_ACCOUNT_URL and grant the application managed identity access to the private container.");
        return new BlobServiceClientBuilder().endpoint(endpoint).credential(new DefaultAzureCredentialBuilder().build()).buildClient().getBlobContainerClient(container);
    }
    @Override public void put(String key,byte[] content,String contentType){var blob=client().getBlobClient(key);blob.upload(BinaryData.fromBytes(content),false);blob.setHttpHeaders(new BlobHttpHeaders().setContentType(contentType));}
    @Override public byte[] get(String key){return client().getBlobClient(key).downloadContent().toBytes();}
    @Override public void delete(String key){client().getBlobClient(key).deleteIfExists();}
}
