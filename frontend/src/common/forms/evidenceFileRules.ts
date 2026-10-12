export const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
const IMAGE_EXTENSIONS = new Set(["jpg", "jpeg", "png", "webp"]);

export type UploadFieldRules = {
  type: string;
  fileRules?: { extensions?: string[]; mimeTypes?: string[]; maxFileSizeBytes?: number } | null;
};

export function isImageFile(file: Pick<File, "name" | "type">, field: UploadFieldRules): boolean {
  const extension = file.name.split(".").pop()?.toLowerCase() || "";
  return field.type === "IMAGE_UPLOAD" || IMAGE_EXTENSIONS.has(extension) || file.type.startsWith("image/");
}

export function maxBytesForFile(file: Pick<File, "name" | "type">, field: UploadFieldRules): number {
  const configured = field.fileRules?.maxFileSizeBytes || MAX_IMAGE_BYTES;
  return isImageFile(file, field) ? Math.min(configured, MAX_IMAGE_BYTES) : configured;
}

export function validateEvidenceFile(file: File, field: UploadFieldRules): string | null {
  const rules = field.fileRules;
  const extension = file.name.split(".").pop()?.toLowerCase() || "";
  const allowedExtension = rules?.extensions?.some(value => value.toLowerCase() === extension) || false;
  const allowedMime = rules?.mimeTypes?.some(value => value.toLowerCase() === file.type.toLowerCase()) || false;
  if (field.type === "IMAGE_UPLOAD" && (!IMAGE_EXTENSIONS.has(extension) || !file.type.toLowerCase().startsWith("image/"))) {
    return `${file.name} is not a supported image type for this form.`;
  }
  if (!allowedExtension || !allowedMime) return `${file.name} is not an allowed image type for this form.`;
  if (file.size > maxBytesForFile(file, field)) {
    return isImageFile(file, field)
      ? "This image exceeds the 5 MB per-file limit. Please choose a smaller image."
      : `${file.name} exceeds the configured per-file size limit.`;
  }
  return null;
}

export function partitionEvidenceFiles<T extends File>(files: readonly T[], field: UploadFieldRules) {
  const accepted: T[] = []; const rejected: Array<{ file: T; message: string }> = [];
  for (const file of files) {
    const message = validateEvidenceFile(file, field);
    if (message) rejected.push({ file, message }); else accepted.push(file);
  }
  return { accepted, rejected };
}

export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(2).replace(/0+$/, "").replace(/\.$/, "")} MB`;
}
