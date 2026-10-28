type FieldRules = {
  textRules?: unknown;
  numericRules?: unknown;
  dateRules?: unknown;
  fileRules?: unknown;
};

/** Keep only validation settings that apply to a field's resulting type. */
export function rulesForFieldType<T extends FieldRules>(field: T, type: string): Pick<T, "textRules" | "numericRules" | "dateRules" | "fileRules"> {
  const isText = type === "TEXT" || type === "TEXTAREA";
  const isUpload = type === "FILE_UPLOAD" || type === "IMAGE_UPLOAD";
  return {
    textRules: isText ? field.textRules ?? null : null,
    numericRules: type === "NUMBER" ? field.numericRules ?? null : null,
    dateRules: type === "DATE" ? field.dateRules ?? null : null,
    fileRules: isUpload ? field.fileRules ?? null : null,
  } as Pick<T, "textRules" | "numericRules" | "dateRules" | "fileRules">;
}
