package net.talaatharb.expanse.connector;

import lombok.Getter;
import net.talaatharb.expanse.dto.ApiModels.ApiError;

@Getter
public final class ConnectorException extends RuntimeException {
    private final int status;
    private final ApiError error;

    public ConnectorException(int status, ApiError error) {
        super(error.code() + ": " + error.message()
                + (error.currentRevision() == null ? "" : " (current revision " + error.currentRevision() + ")"));
        this.status = status;
        this.error = error;
    }
}
