package dev.mcdevmcp.storage.migration;

import java.util.Objects;

public value record SourcePreparationStamp(int schemaVersion, SourceOwnership ownership, SourceProducerIdentity producer, SourceInputIdentity inputs, SourceTreeInventory inventory, SourceValidation validation) {
    public SourcePreparationStamp {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("Unsupported source preparation stamp schema: " + schemaVersion);
        }
        Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(validation, "validation");
        if (!inventory.present() || !validation.valid()) {
            throw new IllegalArgumentException("Source preparation stamp requires validated complete sources");
        }
        if (ownership == SourceOwnership.GENERATED && producer == null || ownership != SourceOwnership.GENERATED && producer != null) {
            throw new IllegalArgumentException("Only generated sources carry producer ownership");
        }
    }
}
