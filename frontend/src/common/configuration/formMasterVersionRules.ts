export type FormVersionStatus = "DRAFT" | "PUBLISHED" | string;

export function canRestoreAsDraft(status: FormVersionStatus): boolean {
  return status === "PUBLISHED";
}

export function isCurrentPublishedVersion(versionId: string, publishedVersionId: string | null): boolean {
  return Boolean(publishedVersionId && versionId === publishedVersionId);
}

export function emptyDraftPreviewMessage(publishedVersion: number | null): string {
  return `This draft is empty. Its preview does not affect the current published version${publishedVersion ? ` v${publishedVersion}` : ""}.`;
}

export function canPermanentlyDeleteForm(publishedVersionId: string | null, versions: readonly { status: FormVersionStatus }[]): boolean {
  return !publishedVersionId && versions.every(version => version.status !== "PUBLISHED");
}
