import { formatDateTime } from "@/common/dateTime";

export type EvidenceDocument = {
  id: string;
  filename: string;
  sizeBytes: number;
  scanStatus: string;
  uploadedAt?: string | null;
  uploadedBy?: string | null;
  fieldKey?: string | null;
  source?: string | null;
};

function description(source?: string | null) {
  if (source === "DEFECT_REPORT") return "Auditor report";
  if (source === "CORRECTIVE_ACTION") return "Maintenance action";
  return source ? source.replaceAll("_", " ") : "Case evidence · source unavailable";
}

export default function EvidenceDocumentCard({ file, onView }: { file: EvidenceDocument; onView: () => void }) {
  return <div className="flex min-w-0 flex-wrap items-center justify-between gap-3 rounded-xl border border-[#e3eae5] bg-white p-3">
    <span className="grid min-w-0 gap-1">
      <small className="text-xs font-semibold text-[#315b43]">Case evidence</small>
      <strong className="break-all text-sm">{file.filename}</strong>
      <small className="break-words text-xs text-[#000000]">{description(file.source)}</small>
      <small className="break-words text-xs text-[#000000]">Uploaded by {file.uploadedBy || "uploader unavailable"} · {formatDateTime(file.uploadedAt)}</small>
      <small className="break-all text-xs text-[#000000]">{file.fieldKey || "evidence"} · {(file.sizeBytes / 1024 / 1024).toFixed(1)} MB</small>
    </span>
    <button type="button" className="button-secondary" disabled={file.scanStatus === "REJECTED"} onClick={onView}>
      {file.scanStatus === "REJECTED" ? "Unavailable" : "View"}
    </button>
  </div>;
}
