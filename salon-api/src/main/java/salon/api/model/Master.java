package salon.api.model;

import io.avaje.validation.constraints.NotBlank;
import io.avaje.validation.constraints.NotNull;

public record Master(
    Long id,

    @NotBlank String firstName,      // Protects the first part of our Natural Key
    @NotBlank String lastName,       // Protects the second part of our Natural Key

    String specialization,           // Can be null or empty initially
    @NotNull Boolean isActive        // Enforces explicit true/false state assignment
) {
  public String getFullName() {
    return firstName + " " + lastName;
  }
}
