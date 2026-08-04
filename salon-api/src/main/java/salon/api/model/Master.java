package salon.api.model;

import io.avaje.validation.constraints.NotBlank;

public record Master(
    Long id,

    @NotBlank String firstName,      // Protects the first part of our Natural Key
    @NotBlank String lastName,       // Protects the second part of our Natural Key

    String specialization           // Can be null or empty initially
) {
  public String getFullName() {
    return firstName + " " + lastName;
  }
}
