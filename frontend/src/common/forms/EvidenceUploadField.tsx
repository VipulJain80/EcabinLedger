"use client";

import { ChangeEvent, DragEvent, useEffect, useRef, useState } from "react";
import Image from "next/image";
import { formatFileSize } from "./evidenceFileRules";

type UploadedFile = { id: string; filename: string; sizeBytes?: number; scanStatus: string };

export default function EvidenceUploadField({
  fieldKey, label, type, required, disabled, accept, multiple, maxCount, minCount, pendingFiles, errors, uploaded, uploading, form,
  onSelect, onRemovePending, onRetry, onRemoveUploaded, onDismissError,
}: {
  fieldKey: string; label: string; type: string; required: boolean; disabled: boolean; accept: string; multiple: boolean;
  maxCount: number; minCount: number; pendingFiles: File[]; errors: Record<string,string>; uploaded: UploadedFile[]; uploading: boolean; form?: string;
  onSelect: (files: File[]) => void; onRemovePending: (file: File) => void; onRetry: (file: File) => void;
  onRemoveUploaded: (file: UploadedFile) => void; onDismissError: (errorKey: string) => void;
}) {
  const input = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  const [previews, setPreviews] = useState<Record<string, string>>({});
  useEffect(() => {
    const urls: string[] = [];
    const next: Record<string, string> = {};
    for (const file of pendingFiles) {
      if (file.type.startsWith("image/")) {
        const key = fileKey(file); const url = URL.createObjectURL(file); urls.push(url); next[key] = url;
      }
    }
    const update = window.setTimeout(() => setPreviews(next), 0);
    return () => { window.clearTimeout(update); urls.forEach(URL.revokeObjectURL); };
  }, [pendingFiles]);

  function select(files: File[]) { if (files.length) onSelect(files); }
  function onChange(event: ChangeEvent<HTMLInputElement>) { select(Array.from(event.target.files || [])); event.target.value = ""; }
  function onDrop(event: DragEvent<HTMLDivElement>) { event.preventDefault(); setDragging(false); if (!disabled) select(Array.from(event.dataTransfer.files || [])); }

  return <div className="evidence-upload-field" data-field-key={fieldKey}>
    <div className={`evidence-dropzone${dragging ? " is-dragging" : ""}${disabled ? " is-disabled" : ""}`}
      onDragOver={event => { event.preventDefault(); if (!disabled) setDragging(true); }}
      onDragLeave={() => setDragging(false)} onDrop={onDrop}>
      <div className="evidence-upload-icon" aria-hidden="true">⇧</div>
      <div className="evidence-dropzone-copy"><strong>Drag {type === "IMAGE_UPLOAD" ? "images" : "files"} here or browse files</strong>
        <span>{type === "IMAGE_UPLOAD" ? "Upload evidence images. Maximum 5 MB per image." : "Upload evidence files. Images are limited to 5 MB per file."}</span>
        <small>Each image is validated individually. Supported formats: {accept.split(",").map(value => value.trim().replace(/^image\//, "")).filter(Boolean).join(", ").toUpperCase()}.</small>
        {required && <small className="evidence-required-note">Required · add at least {minCount} image{minCount === 1 ? "" : "s"}.</small>}
        <small>{pendingFiles.length + uploaded.length} of {maxCount} file{maxCount === 1 ? "" : "s"} selected</small>
      </div>
      <button type="button" className="button-secondary" onClick={() => input.current?.click()} disabled={disabled || uploading}>Browse files</button>
      <input ref={input} id={`evidence-${fieldKey}`} form={form} type="file" className="evidence-file-input" accept={accept} multiple={multiple}
        required={Boolean(form && required && pendingFiles.length + uploaded.filter(item => item.scanStatus === "CLEAN").length < minCount)}
        disabled={disabled || uploading} onChange={onChange} aria-label={`Browse files for ${label}`} aria-describedby={`evidence-errors-${fieldKey}`} />
    </div>
    {Object.entries(errors).length > 0 && <ul id={`evidence-errors-${fieldKey}`} className="configuration-field-error evidence-selection-errors" aria-live="assertive">
      {Object.entries(errors).map(([errorKey, error]) => <li key={errorKey}><span>{error}</span><button type="button" onClick={() => onDismissError(errorKey)} aria-label={`Dismiss upload message: ${error}`}>Dismiss</button></li>)}
    </ul>}
    {uploading && <p className="evidence-upload-progress" role="status"><span className="evidence-spinner" aria-hidden="true"/> Uploading and scanning images…</p>}
    {(pendingFiles.length > 0 || uploaded.length > 0) && <ul className="evidence-file-list" aria-label={`${label} files`}>
      {pendingFiles.map(file => {const error=errors[fileKey(file)];return <li key={fileKey(file)} className="evidence-file-item">
        {previews[fileKey(file)] && <Image unoptimized src={previews[fileKey(file)]} width={64} height={52} alt={`Preview of ${file.name}`} />}
        <span className="evidence-file-meta"><strong>{file.name}</strong><small>{formatFileSize(file.size)} · {error ? "Upload failed" : "Ready to upload"}</small>
          {error && <small className="configuration-field-error" role="alert">{error}</small>}</span>
        {error && <button type="button" className="button-secondary" onClick={() => onRetry(file)} disabled={disabled || uploading}>Retry</button>}
        <button type="button" className="evidence-remove-button" onClick={() => onRemovePending(file)} disabled={uploading} aria-label={`Remove ${file.name}`}>Remove</button>
      </li>;})}
      {uploaded.map(file => <li key={file.id} className="evidence-file-item">
        <span className="evidence-file-thumbnail" aria-hidden="true">▧</span>
        <span className="evidence-file-meta"><strong>{file.filename}</strong><small>{file.sizeBytes == null ? "" : `${formatFileSize(file.sizeBytes)} · `}{file.scanStatus === "CLEAN" ? "Uploaded and scan passed" : `${file.scanStatus} · not ready to submit`}</small></span>
        <button type="button" className="evidence-remove-button" onClick={() => onRemoveUploaded(file)} disabled={uploading} aria-label={`Remove uploaded file ${file.filename}`}>Remove</button>
      </li>)}
    </ul>}
    <span className="visually-hidden" aria-live="polite">{`${pendingFiles.length + uploaded.length} ${type === "IMAGE_UPLOAD" ? "image" : "file"}${pendingFiles.length + uploaded.length === 1 ? "" : "s"} selected.`}</span>
  </div>;
}

function fileKey(file: File) { return `${file.name}:${file.size}:${file.lastModified}`; }
