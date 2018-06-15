package lightcode.server;

import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;

import java.util.concurrent.CompletableFuture;

public interface LightCodeExtension {
    @JsonRequest
    CompletableFuture<String> repl(Object message);
}