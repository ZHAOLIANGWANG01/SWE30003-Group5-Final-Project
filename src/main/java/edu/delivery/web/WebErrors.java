package edu.delivery.web;

import edu.delivery.contract.StoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

@ControllerAdvice
public class WebErrors {
  private static final Logger LOG = LoggerFactory.getLogger(WebErrors.class);

  public record ErrorView(String code, String message) {}

  @ExceptionHandler(StoreException.class)
  @ResponseBody
  public ResponseEntity<ErrorView> storageError(StoreException error) {
    HttpStatus status =
        switch (error.code()) {
          case NOT_FOUND -> HttpStatus.NOT_FOUND;
          case DUPLICATE, STALE_DATA, CONFLICT -> HttpStatus.CONFLICT;
          case INVALID_DATA -> HttpStatus.BAD_REQUEST;
          case STORAGE_ERROR, TRANSACTION_REQUIRED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    String message = error.getMessage();
    if (status.is5xxServerError()) {
      LOG.error("Storage operation failed: {}", error.code(), error);
      message = "The operation could not be completed. Please contact the team.";
    }
    return ResponseEntity.status(status).body(new ErrorView(error.code().name(), message));
  }
}
