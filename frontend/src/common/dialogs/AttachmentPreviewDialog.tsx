"use client";

import DialogPortal from "./DialogPortal";

export type AttachmentPreviewFile = { filename:string; mimeType:string; sizeBytes:number };

export default function AttachmentPreviewDialog({file,url,loading=false,error,downloading=false,onDownload,onClose}:{
  file:AttachmentPreviewFile;
  url?:string;
  loading?:boolean;
  error?:string;
  downloading?:boolean;
  onDownload:()=>void;
  onClose:()=>void;
}) {
  const mimeType=file.mimeType.toLowerCase();
  return <DialogPortal onClose={onClose} labelledBy="attachment-preview-title" className="w-full max-w-5xl overflow-hidden rounded-2xl border border-[#dce5df] bg-white shadow-2xl">
    <header className="flex items-center justify-between gap-4 border-b border-[#e7ede9] bg-white px-5 py-4 sm:px-6">
      <div className="flex min-w-0 items-center gap-3">
        <span aria-hidden="true" className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-[#edf5ef] text-[#28563d]">
          <svg viewBox="0 0 24 24" fill="none" className="h-5 w-5" stroke="currentColor" strokeWidth="1.8"><path strokeLinecap="round" strokeLinejoin="round" d="M7 3.75h6l5 5v11.5H7a2 2 0 0 1-2-2v-12.5a2 2 0 0 1 2-2Z"/><path strokeLinecap="round" strokeLinejoin="round" d="M13 3.75v5h5M8.5 13h7M8.5 16.5h7"/></svg>
        </span>
        <div className="min-w-0">
          <h2 id="attachment-preview-title" className="m-0 break-all text-base font-bold text-[#17251d] sm:text-lg">{file.filename}</h2>
          <p className="mb-0 mt-1 text-xs text-[#000000] sm:text-sm">{file.mimeType} <span aria-hidden="true">·</span> {(file.sizeBytes/1024/1024).toFixed(2)} MB</p>
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-2">
        <button type="button" onClick={onDownload} disabled={!url||loading||Boolean(error)||downloading} aria-busy={downloading} className="button-primary inline-flex min-h-10 items-center gap-2 rounded-xl px-4 text-sm font-semibold disabled:cursor-not-allowed disabled:opacity-55">
          <svg aria-hidden="true" viewBox="0 0 20 20" fill="none" className="h-4 w-4" stroke="currentColor" strokeWidth="1.8"><path strokeLinecap="round" strokeLinejoin="round" d="M10 2.75v9m0 0 3.25-3.25M10 11.75 6.75 8.5M3.75 13.25v2A1.75 1.75 0 0 0 5.5 17h9a1.75 1.75 0 0 0 1.75-1.75v-2"/></svg>
          {downloading?"Downloading…":"Download"}
        </button>
        <button type="button" onClick={onClose} aria-label="Close evidence preview" title="Close" className="grid h-10 w-10 shrink-0 place-items-center rounded-xl border border-[#d7e0da] bg-white text-[#26352c] transition hover:bg-[#f3f6f4] active:bg-[#e8eee9] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#3165bd]">
          <svg aria-hidden="true" viewBox="0 0 20 20" fill="none" className="h-4 w-4" stroke="currentColor" strokeWidth="2"><path strokeLinecap="round" d="m5 5 10 10M15 5 5 15"/></svg>
        </button>
      </div>
    </header>
    <div className="grid max-h-[75vh] min-h-48 place-items-center overflow-auto bg-[#f3f5f4] p-3 sm:p-5">
      {loading?<p role="status" className="m-0 text-sm text-[#000000]">Loading preview…</p>:error?<p role="alert" className="m-0 text-sm text-[#9a4035]">{error}</p>:url&&mimeType.startsWith("image/")?<img src={url} alt={file.filename} className="max-h-[68vh] max-w-full object-contain"/>:url&&mimeType==="application/pdf"?<iframe title={`Preview of ${file.filename}`} src={url} className="h-[68vh] w-full rounded-lg border border-[#dce5df] bg-white"/>:<p className="m-0 max-w-xl text-center text-sm text-[#000000]">Preview is not available for this file type. You can download the original file.</p>}
    </div>
  </DialogPortal>;
}
