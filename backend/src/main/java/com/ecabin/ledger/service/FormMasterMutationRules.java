package com.ecabin.ledger.service;

import com.ecabin.ledger.api.FormModels.FormVersion;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Small deterministic rules used by Form Master mutations. */
final class FormMasterMutationRules {
    private FormMasterMutationRules() {}

    static void requireExplicitDraftReplacement(boolean hasDraft, boolean replaceExistingDraft) {
        if (hasDraft && !replaceExistingDraft) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This form already has an editable draft. Choose to keep it, cancel, or explicitly replace it with the restored copy.");
        }
    }

    static void requireDraftVersionDelete(String status, boolean isPublishedPointer) {
        if (!"DRAFT".equals(status) || isPublishedPointer) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only an unpublished draft version can be deleted.");
        }
    }

    static String normalizeKey(String raw) {
        String key=raw==null?"":raw.trim().toUpperCase(java.util.Locale.ROOT);
        if(!key.matches("[A-Z][A-Z0-9_-]{1,79}"))throw new IllegalArgumentException("Form key must use 2–80 uppercase letters, numbers, underscores or hyphens.");
        return key;
    }

    static String validateName(String raw) {
        String name=raw==null?"":raw.trim();
        if(name.isEmpty()||name.length()>160)throw new IllegalArgumentException("Form name must contain 1 to 160 characters.");
        return name;
    }

    /** Published pointer is the implicit default. Unpublished work is never chosen without an explicit ID. */
    static FormVersion selectSourceVersion(List<FormVersion> versions,UUID publishedVersionId,UUID explicitVersionId) {
        UUID selected=explicitVersionId==null?publishedVersionId:explicitVersionId;
        if(selected==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"This form has no published version to copy. Select a specific draft version if you intend to copy unpublished work.");
        FormVersion version=versions.stream().filter(candidate->candidate.id().equals(selected)).findFirst()
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Selected source form version was not found for this organization."));
        if(explicitVersionId==null&& !"PUBLISHED".equals(version.status()))throw new ResponseStatusException(HttpStatus.CONFLICT,"The source form's published version pointer is not valid. Select an explicit version or repair the form mapping before copying.");
        return version;
    }
}
