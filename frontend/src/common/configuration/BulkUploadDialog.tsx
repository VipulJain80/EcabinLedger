"use client";

import { DragEvent, useRef, useState } from "react";
import { responseErrorMessage } from "@/common/alerts";
import DialogPortal from "@/common/dialogs/DialogPortal";
import { formatDateTime } from "@/common/dateTime";

type Request = (path: string, init?: RequestInit) => Promise<Response>;
type FieldIssue = { field: string; message: string };
type PreviewRow = { rowNumber: number; values: Record<string,string>; errors: FieldIssue[] };
type Preview = { importId: string; resource: string; validRows: number; rejectedRows: number; expiresAt: string; rows: PreviewRow[] };
type Result = { created: number; updated: number; skipped: number; rejected: number };

function safeCsv(value: string) {
  const safe = /^[\s\u0000-\u001f]*[=+\-@]/.test(value) ? `'${value}` : value;
  return `"${safe.replaceAll('"', '""')}"`;
}

function download(text: string, filename: string, type: string) {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const anchor = document.createElement("a"); anchor.href = url; anchor.download = filename; anchor.click();
  URL.revokeObjectURL(url);
}

export async function downloadTemplateCsv(request: Request, resource: string) {
  const response = await request(`/configuration/${resource}/import-template`);
  if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not download the sample CSV."));
  const blob = await response.blob();
  const disposition = response.headers.get("content-disposition");
  const filename = disposition?.match(/filename\*=UTF-8''([^;]+)|filename="?([^";]+)"?/i);
  download(await blob.text(), decodeURIComponent(filename?.[1] || filename?.[2] || `ecabin-${resource}-sample.csv`), "text/csv;charset=utf-8");
}

export default function BulkUploadDialog({ resource, label, request, onClose, onImported }: {
  resource: string; label: string; request: Request; onClose: () => void; onImported: () => Promise<void>;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [result, setResult] = useState<Result | null>(null);
  const [busy, setBusy] = useState<"template"|"validate"|"commit"|null>(null);
  const [error, setError] = useState("");

  async function downloadTemplate() {
    setBusy("template"); setError("");
    try {
      await downloadTemplateCsv(request, resource);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "Could not download the sample CSV."); }
    finally { setBusy(null); }
  }

  async function validate() {
    if (!file) { setError("Choose a CSV file first."); return; }
    if (file.size > 5 * 1024 * 1024) { setError("CSV files must be 5 MB or smaller."); return; }
    setBusy("validate"); setError(""); setPreview(null); setResult(null);
    try {
      const body = new FormData(); body.set("file", file);
      const response = await request(`/configuration/${resource}/imports/validate`, { method: "POST", body });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "CSV validation failed."));
      setPreview(await response.json() as Preview);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "CSV validation failed."); }
    finally { setBusy(null); }
  }

  async function commit() {
    if (!preview || preview.validRows === 0 || busy) return;
    setBusy("commit"); setError("");
    try {
      const response = await request(`/configuration/${resource}/imports`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ importId: preview.importId }) });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "The import was not committed."));
      setResult(await response.json() as Result);
      await onImported();
    } catch (reason) { setError(reason instanceof Error ? reason.message : "The import was not committed."); }
    finally { setBusy(null); }
  }

  function downloadErrors() {
    if (!preview) return;
    const rejected=preview.rows.filter((row)=>row.errors.length>0);
    if (!rejected.length) return;
    const sourceColumns=[...new Set(rejected.flatMap((row)=>Object.keys(row.values)))];
    const headers=["csv_row",...sourceColumns,"error_reason"];
    const csv=[headers.map(safeCsv).join(","),...rejected.map(row=>[String(row.rowNumber),...headers.slice(1,-1).map((header)=>row.values[header]||""),row.errors.map(issue=>`${issue.field}: ${issue.message}`).join("; ")].map(safeCsv).join(","))].join("\r\n")+"\r\n";
    download(csv,`ecabin-${resource}-rejected-rows.csv`,"text/csv;charset=utf-8");
  }

  function drop(event: DragEvent<HTMLDivElement>) { event.preventDefault(); const dropped=event.dataTransfer.files?.[0]; if(dropped) { setFile(dropped);setPreview(null);setResult(null);setError(""); } }

  return <DialogPortal onClose={() => { if (!busy) onClose(); }} closeOnEscape={!busy} labelledBy="bulk-upload-title" className="modal bulk-upload-modal gap-[14px]">
    <div className="modal-heading flex items-start justify-between mb-[18px]"><div><div className="eyebrow">MASTER DATA IMPORT</div><h2 id="bulk-upload-title">Bulk upload · {label}</h2></div><button className="close" type="button" onClick={onClose} disabled={Boolean(busy)} aria-label="Close">×</button></div>
    <div className="bulk-upload-instructions flex items-center justify-between gap-3 max-[700px]:items-start max-[700px]:flex-col"><p>CSV · UTF-8 · maximum 5 MB and 5,000 rows · create-only mode. Existing records are never overwritten.</p><button type="button" className="button-secondary" onClick={()=>void downloadTemplate()} disabled={Boolean(busy)}>{busy==="template"?"Preparing…":"Download Sample CSV"}</button></div>
    <div className="bulk-dropzone grid place-content-center gap-[7px]" onDragOver={(event)=>event.preventDefault()} onDrop={drop} onClick={()=>inputRef.current?.click()} role="button" tabIndex={0} onKeyDown={(event)=>{if(event.key==="Enter"||event.key===" "){event.preventDefault();inputRef.current?.click();}}} aria-label="Select or drop a CSV file">
      <input ref={inputRef} type="file" accept=".csv,text/csv" aria-label="CSV file" onClick={(event)=>event.stopPropagation()} onChange={(event)=>{setFile(event.target.files?.[0]||null);setPreview(null);setResult(null);setError("");}} />
      <strong>{file?file.name:"Drop a CSV file here or browse"}</strong><small>{file?`${(file.size/1024).toFixed(1)} KB · CSV`:"Select a .csv file to validate its headers and rows"}</small>
    </div>
    {error&&<div className="bulk-inline-error" role="alert">{error}</div>}
    {preview&&<div className="bulk-preview grid min-h-0 flex-1 gap-[10px]" aria-live="polite"><div className="bulk-summary flex flex-wrap items-center gap-[14px]"><span className="bulk-valid">{preview.validRows} valid</span><span className={preview.rejectedRows?"bulk-invalid":"bulk-valid"}>{preview.rejectedRows} rejected</span><span>Review expires {formatDateTime(preview.expiresAt)}</span></div>
      <div className="bulk-preview-table-wrap"><table className="bulk-preview-table"><thead><tr><th>CSV row</th><th>Values</th><th>Validation</th></tr></thead><tbody>{preview.rows.slice(0,100).map((row)=><tr key={row.rowNumber} className={row.errors.length?"invalid":"valid"}><td>{row.rowNumber}</td><td>{Object.entries(row.values).filter(([,value])=>value).map(([key,value])=><span key={key}><b>{key}</b>: {value} </span>)}</td><td>{row.errors.length?row.errors.map((issue)=>`${issue.field}: ${issue.message}`).join("; "):"Ready to import"}</td></tr>)}</tbody></table>{preview.rows.length>100&&<small>Showing first 100 of {preview.rows.length} rows. The full error report is downloadable.</small>}</div>
      {preview.rejectedRows>0&&<button className="button-secondary" type="button" onClick={downloadErrors}>Download Error CSV</button>}
    </div>}
    {result&&<div className="bulk-result" role="status"><strong>Import confirmed by the server.</strong><span>Created {result.created} · Updated {result.updated} · Skipped {result.skipped} · Rejected {result.rejected}</span></div>}
    <div className="modal-actions flex justify-end gap-2 border-t border-[#edf0ec] pt-[14px] mt-[13px]"><button type="button" className="button-secondary" onClick={onClose} disabled={Boolean(busy)}>{result?"Close":"Cancel"}</button>{!preview&&<button type="button" className="button-secondary" onClick={()=>void validate()} disabled={Boolean(busy)||!file}>{busy==="validate"?"Validating…":"Validate & preview"}</button>}{preview&&!result&&<button type="button" className="button-primary" onClick={()=>void commit()} disabled={Boolean(busy)||preview.validRows===0}>{busy==="commit"?"Importing…":`Confirm import (${preview.validRows})`}</button>}{preview&&!result&&<button type="button" className="button-secondary" onClick={()=>{setPreview(null);setFile(null);setError("");}}>Choose another file</button>}</div>
  </DialogPortal>;
}
