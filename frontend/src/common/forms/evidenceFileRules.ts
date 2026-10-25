export const BYTES_PER_MEGABYTE = 1024 * 1024;
export const MAX_IMAGE_BYTES = 20 * BYTES_PER_MEGABYTE;
export const MAX_UPLOAD_BYTES = 20 * BYTES_PER_MEGABYTE;
const IMAGE_EXTENSIONS = new Set(["jpg", "jpeg", "png", "webp"]);

export type UploadFieldRules = {
  type: string;
  fileRules?: { extensions?: string[]; mimeTypes?: string[]; maxFileSizeBytes?: number; minCount?: number; maxCount?: number; maxTotalSizeBytes?: number | null } | null;
};

export function isImageFile(file: Pick<File, "name" | "type">, field: UploadFieldRules): boolean {
  const extension = file.name.split(".").pop()?.toLowerCase() || "";
  return field.type === "IMAGE_UPLOAD" || IMAGE_EXTENSIONS.has(extension) || file.type.startsWith("image/");
}

export function maxBytesForFile(file: Pick<File, "name" | "type">, field: UploadFieldRules): number {
  const configured = field.fileRules?.maxFileSizeBytes || MAX_UPLOAD_BYTES;
  return isImageFile(file,field) ? Math.min(configured,MAX_IMAGE_BYTES) : Math.min(configured,MAX_UPLOAD_BYTES);
}

/** Older published JSON has no combined limit; its previous per-file × count ceiling remains valid. */
export function maxTotalBytesForField(field: UploadFieldRules): number {
  const rules=field.fileRules;
  if(rules?.maxTotalSizeBytes!=null&&Number.isSafeInteger(rules.maxTotalSizeBytes)&&rules.maxTotalSizeBytes>0)return rules.maxTotalSizeBytes;
  const perFile=rules?.maxFileSizeBytes||MAX_UPLOAD_BYTES;
  const count=rules?.maxCount||1;
  const derived=perFile*count;
  return Number.isSafeInteger(derived)&&derived>0?derived:Number.MAX_SAFE_INTEGER;
}

export function validateEvidenceFile(file: Pick<File, "name" | "type" | "size">, field: UploadFieldRules): string | null {
  const rules = field.fileRules;
  const extension = file.name.split(".").pop()?.toLowerCase() || "";
  const allowedExtension = rules?.extensions?.some(value => value.toLowerCase() === extension) || false;
  const allowedMime = rules?.mimeTypes?.some(value => value.toLowerCase() === file.type.toLowerCase()) || false;
  if (field.type === "IMAGE_UPLOAD" && (!IMAGE_EXTENSIONS.has(extension) || !file.type.toLowerCase().startsWith("image/"))) {
    return `${file.name} is not a supported image type for this form.`;
  }
  if (!allowedExtension || !allowedMime) return `${file.name} is not an allowed file type for this form.`;
  if (file.size > maxBytesForFile(file, field)) {
    return `${file.name} exceeds the configured per-file limit of ${formatMegabytes(maxBytesForFile(file,field))} MB.`;
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

export function partitionEvidenceSelection<T extends Pick<File,"name"|"type"|"size">>(existing: readonly {size?:number;sizeBytes?:number;scanStatus?:string}[], incoming: readonly T[], field: UploadFieldRules) {
  const accepted:T[]=[];
  const rejected:Array<{file:T;message:string}>=[];
  const maxCount=field.fileRules?.maxCount??Number.MAX_SAFE_INTEGER;
  const maxTotal=maxTotalBytesForField(field);
  let count=existing.filter(item=>item.scanStatus!=="REJECTED").length;
  let total=existing.reduce((sum,item)=>safeAdd(sum,item.sizeBytes??item.size??0),0);
  const individuallyValid:T[]=[];
  for(const file of incoming){
    const issue=validateEvidenceFile(file,field);
    if(issue)rejected.push({file,message:issue});else individuallyValid.push(file);
  }
  const requestedCount=count+individuallyValid.length;
  const requestedTotal=individuallyValid.reduce((sum,file)=>safeAdd(sum,file.size),total);
  if(requestedCount>maxCount){
    individuallyValid.forEach(file=>rejected.push({file,message:`Select no more than ${maxCount} file(s) for this field.`}));
  }else if(requestedTotal>maxTotal){
    const message=`Combined upload size exceeds the configured total limit of ${formatMegabytes(maxTotal)} MB.`;
    individuallyValid.forEach(file=>rejected.push({file,message}));
  }else{
    accepted.push(...individuallyValid);
    count=requestedCount;
    total=requestedTotal;
  }
  return {accepted,rejected,totalBytes:total,count};
}

function safeAdd(left:number,right:number){const result=left+right;return Number.isSafeInteger(result)?result:Number.MAX_SAFE_INTEGER;}
export function formatMegabytes(bytes:number):string{return Number((bytes/BYTES_PER_MEGABYTE).toFixed(2)).toString();}

export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(2).replace(/0+$/, "").replace(/\.$/, "")} MB`;
}
