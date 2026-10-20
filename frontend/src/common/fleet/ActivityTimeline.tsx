import { DefectActivity, formatHistoryDate, newestFirst } from "./defectDetailsModel";
import { formatDateTime } from "@/common/dateTime";
import { WorkflowStatus, WorkflowStatusBadge } from "@/common/workflow/workflowStatuses";

const eventIcon = (type: string) => type.includes("REPORTED") ? "＋" : type.includes("ATTACHMENT") ? "▧" : type.includes("APPROV") || type.includes("CLOSURE") ? "✓" : type.includes("REVIEW") ? "◉" : type.includes("ACTION") || type.includes("MAINTENANCE") ? "⚙" : type.includes("VERIF") || type.includes("INSPECT") ? "⌕" : "•";
const human = (value: string) => value.replaceAll("_", " ").toLowerCase().replace(/\b\w/g, char => char.toUpperCase());
const hasRecordedDetails = (event: DefectActivity) => Boolean(event.details?.submittedDetails?.length || event.details?.maintenanceStatus || event.details?.decision || event.details?.maintenanceTeam || event.details?.slaTargetDate != null || event.details?.previousSlaTargetDate != null || event.details?.actionStatus || event.details?.previousActionStatus || event.details?.maintenanceRemark != null);
const isMaintenanceEvent = (event: DefectActivity) => event.eventType.includes("ACTION") || event.eventType.includes("MAINTENANCE");

type TimelineEvidence = { id: string; filename: string; scanStatus: string };
export default function ActivityTimeline({ events, loading, error, onRetry, statuses = [], attachments = [], onOpenEvidence }: { events: DefectActivity[]; loading: boolean; error?: string; onRetry?: () => void; statuses?: WorkflowStatus[]; attachments?: TimelineEvidence[]; onOpenEvidence?: (id: string) => void }) {
  const sorted = newestFirst(events);
  return <section aria-labelledby="activity-timeline-title" className="min-w-0 rounded-2xl border border-[#e0e8e4] bg-white p-5 shadow-sm">
    <div className="mb-4 flex items-center justify-between gap-3"><div><p className="m-0 text-[11px] font-bold tracking-[.14em] text-[#000000]">AUDIT TRAIL</p><h2 id="activity-timeline-title" className="mb-0 mt-1 text-lg font-bold text-[#000000]">Activity timeline</h2></div><span className="rounded-full bg-[#f1f5f2] px-2.5 py-1 text-xs text-[#000000]">Newest first</span></div>
    {loading ? <p role="status" className="text-sm text-[#000000]">Loading recorded activity…</p> : error ? <div role="alert" className="text-sm text-[#9a4035]"><p>{error}</p>{onRetry && <button type="button" onClick={onRetry} className="rounded-lg border border-[#d9e2dd] px-3 py-2 font-semibold text-[#000000] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#3165bd]">Retry</button>}</div> : sorted.length === 0 ? <p className="text-sm text-[#000000]">No activity has been recorded for this defect.</p> : <ol className="m-0 list-none border-l border-[#dfe7e2] p-0">{sorted.map((event, index) => <li key={event.id} className="relative pb-5 pl-5 last:pb-0">
      <span aria-hidden="true" className={`absolute -left-[13px] top-0 grid h-6 w-6 place-items-center rounded-full border bg-white text-xs ${index === 0 ? "border-[#91ae9b] text-[#38634b]" : "border-[#dbe4df] text-[#000000]"}`}>{eventIcon(event.eventType)}</span>
      <details className="group" open={hasRecordedDetails(event)}>
        <summary className="cursor-pointer list-none rounded-md focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#3165bd]"><span className="block text-sm font-semibold text-[#000000]">{human(event.eventType)}{event.toStatus ? <> · <WorkflowStatusBadge statusCode={event.toStatus} statuses={statuses}/></> : ""}</span><span className="mt-1 block text-xs text-[#000000]">{event.actorId} · {formatDateTime(event.occurredAt)}</span></summary>
        <div className="mt-2 break-words rounded-lg bg-[#f7faf8] p-3 text-sm text-[#000000]">
          {event.details?.formName && <p className="m-0 mb-2 text-xs font-semibold">Form: {event.details.formName}{event.details.formPurpose ? ` · ${human(event.details.formPurpose)}` : ""}{event.details.formVersionNumber ? ` · Version ${event.details.formVersionNumber}` : ""}</p>}
          {hasRecordedDetails(event) ? <section aria-label="Submitted details" className="mt-2">
            <h3 className="m-0 mb-2 text-xs font-bold">Submitted details</h3>
            <dl className="m-0 grid min-w-0 grid-cols-[minmax(100px,.7fr)_minmax(0,1.3fr)] gap-x-3 gap-y-1.5 text-xs">
              {event.details?.submittedDetails?.map((item,index)=><Detail key={`${item.label}-${index}`} label={item.label} value={item.value}/ >)}
              {event.details?.maintenanceStatus && <Detail label="Maintenance status" value={human(event.details.maintenanceStatus)}/ >}
              {event.details?.decision && <Detail label="Decision" value={human(event.details.decision)}/ >}
              {event.details?.maintenanceTeam && <Detail label="Maintenance team" value={event.details.maintenanceTeam}/ >}
              {event.details?.slaTargetDate != null && <Detail label="SLA target date" value={formatHistoryDate(event.details.slaTargetDate,event.details.previousSlaTargetDate?"Cleared":"Not set")}/ >}
              {event.details?.maintenanceRemark != null && <Detail label="Remark" value={event.details.maintenanceRemark || (isMaintenanceEvent(event) && event.note ? event.note : "Not provided")}/ >}
              {event.details?.maintenanceRemark == null && isMaintenanceEvent(event) && event.note && <Detail label="Remark" value={event.note}/ >}
            </dl>
          </section> : null}
          {(event.fromStatus || event.details?.actionStatus || event.details?.previousActionStatus || event.details?.previousSlaTargetDate != null) && <section aria-label="Changes" className="mt-3 border-t border-[#e1e8e3] pt-2">
            <h3 className="m-0 mb-2 text-xs font-bold">Changes</h3>
            <dl className="m-0 grid min-w-0 grid-cols-[minmax(100px,.7fr)_minmax(0,1.3fr)] gap-x-3 gap-y-1.5 text-xs">
              {event.fromStatus && <Detail label="Case status" value={`${human(event.fromStatus)} → ${event.toStatus ? human(event.toStatus) : "—"}`}/ >}
              {event.details?.previousActionStatus && <Detail label="Maintenance state" value={`${human(event.details.previousActionStatus)} → ${event.details.actionStatus ? human(event.details.actionStatus) : "—"}`}/ >}
              {event.details?.previousSlaTargetDate != null && <Detail label="Previous SLA target" value={formatHistoryDate(event.details.previousSlaTargetDate)}/ >}
            </dl>
          </section>}
          {event.note && event.note !== event.details?.maintenanceRemark && !(isMaintenanceEvent(event) && event.details?.maintenanceRemark != null) && !(event.eventType === "FORM_SUBMITTED" && event.details?.submittedDetails?.length) && <p className="m-0 mt-2 whitespace-pre-wrap">{event.note}</p>}
          {event.inspectionOutcome && <p className="mb-0 mt-2 text-xs">Inspection result: {human(event.inspectionOutcome)}</p>}
          {event.evidenceReference && <p className="mb-0 mt-2 break-all text-xs">Evidence reference: {event.evidenceReference}</p>}
          {event.details?.evidenceAttachmentId && attachments.find(item=>item.id===event.details?.evidenceAttachmentId) && (()=>{const file=attachments.find(item=>item.id===event.details?.evidenceAttachmentId)!;return <button type="button" disabled={file.scanStatus==="REJECTED"||!onOpenEvidence} onClick={()=>onOpenEvidence?.(file.id)} className="mt-2 rounded-lg border border-[#dce5df] px-3 py-2 text-xs font-semibold text-[#000000] disabled:cursor-not-allowed disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#3165bd]">{file.scanStatus==="REJECTED"?"Evidence unavailable":`Open evidence · ${file.filename}`}</button>;})()}
        </div>
      </details>
    </li>)}</ol>}
  </section>;
}

function Detail({label,value}:{label:string;value:string}) { return <><dt className="font-medium text-[#000000]">{label}</dt><dd className="m-0 min-w-0 whitespace-pre-wrap break-words font-semibold text-[#000000]">{value}</dd></>; }
