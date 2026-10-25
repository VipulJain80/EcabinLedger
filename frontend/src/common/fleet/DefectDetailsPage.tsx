"use client";

import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter, useSearchParams } from "next/navigation";
import { alert, responseErrorMessage } from "@/common/alerts";
import { getApplicationAccessToken, applicationBasePath, clearLocalSession } from "@/common/auth/localSession";
import { formatDateTime } from "@/common/dateTime";
import { useDefectWorkflowStatuses, IssueMetadata } from "@/common/workflow/workflowStatuses";
import { formPurposeForStatus } from "@/common/forms/formResolution";
import OperationalFormRenderer from "@/common/forms/OperationalFormRenderer";
import AttachmentPreviewDialog from "@/common/dialogs/AttachmentPreviewDialog";
import GalleyReviewerDecisionForm from "./GalleyReviewerDecisionForm";
import MaintenanceTeamReassignmentDialog, { ReassignTeamButton } from "./MaintenanceTeamReassignmentDialog";
import ActivityTimeline from "./ActivityTimeline";
import { canReassignMaintenance, DefectActivity, defectDetailsRequestPath, safeReturnPath } from "./defectDetailsModel";
import AccessStatePage, { accessFailureMessage } from "@/common/auth/AccessStatePage";

type Defect = { id:string; reference:string; tailNumber:string; aircraftType:string; location:string; category:string; zone:string; rowNumber:number|null; seatReference:string|null; component:string|null; areaId:string; componentId:string|null; conditionId:string|null; conditionName:string|null; title:string; description:string; severity:string; severityName:string; status:string; reportedBy:string; assignedTo:string|null; assignedTeam:string|null; activeActionId:string|null; dueAt:string|null; reportedAt:string; updatedAt:string; routingAttention:string|null };
type Identity = { organizationName:string; applicationName:string; displayName:string; role:string; permissions:string[] };
type Attachment = { id:string; defectId:string; formSubmissionId:string|null; fieldKey:string; filename:string; mimeType:string; sizeBytes:number; scanStatus:string; uploadedAt:string; source?:string|null; uploadedBy?:string|null };
type Props = { defectId:string; aircraftListId?:string|null; onBack?:()=>void };
const API = process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080/api/v1";
const human = (value:string) => value.replaceAll("_"," ").toLowerCase().replace(/\b\w/g,char=>char.toUpperCase());
const hasPermission = (permissions:string[], permission:string) => permissions.includes(permission);
const attachmentDescription = (source?:string|null) => source === "DEFECT_REPORT" ? "Auditor report" : source === "CORRECTIVE_ACTION" ? "Maintenance action" : source ? source.replaceAll("_"," ") : "Case evidence · source unavailable";

async function request(path:string, init:RequestInit = {}) {
  const token=await getApplicationAccessToken();
  const headers=new Headers(init.headers);
  if(token)headers.set("Authorization",`Bearer ${token}`);
  return fetch(`${API}${path}`,{...init,headers,credentials:"omit",cache:"no-store"});
}

export default function DefectDetailsPage({defectId,aircraftListId,onBack}:Props) {
  const workflowStatuses = useDefectWorkflowStatuses(request).data?.items || [];
  const router=useRouter();
  const params=useSearchParams();
  const queryClient=useQueryClient();
  const [attachmentPreview,setAttachmentPreview]=useState<{file:Attachment;url?:string;error?:string}|null>(null);
  const [previewLoading,setPreviewLoading]=useState(false);
  const [downloadingAttachmentId,setDownloadingAttachmentId]=useState<string|null>(null);
  const [reassignmentOpen,setReassignmentOpen]=useState(false);
  const [embeddedTab,setEmbeddedTab]=useState("overview");
  const requestedTab=params.get("tab");
  const tab=onBack?embeddedTab:requestedTab&&["overview","workflow","attachments","history"].includes(requestedTab)?requestedTab:"overview";
  const returnPath=safeReturnPath(params.get("returnTo"),applicationBasePath);
  const identityQuery=useQuery<Identity>({queryKey:["defect-details-identity"],queryFn:async()=>{const response=await request("/me");if(!response.ok)throw new Error(await responseErrorMessage(response,"Your application access could not be verified."));return response.json();},retry:false});
  const identity=identityQuery.data;
  const defectQuery=useQuery<Defect>({queryKey:["defect-details",defectId,aircraftListId||null],enabled:Boolean(identity),queryFn:async()=>{const response=await request(defectDetailsRequestPath(defectId,aircraftListId));if(!response.ok)throw new Error(await responseErrorMessage(response,"This defect is unavailable or you are not authorized to view it."));return response.json();},retry:false});
  const defect=defectQuery.data;
  const activityAllowed=Boolean(identity&&(hasPermission(identity.permissions,"AUDIT")||hasPermission(identity.permissions,"REPORT")||hasPermission(identity.permissions,"PERFORM_ACTION")||hasPermission(identity.permissions,"REVIEW")||hasPermission(identity.permissions,"DEFECT_VIEW_ALL")));
  const eventsQuery=useQuery<DefectActivity[]>({queryKey:["defect-activity",defectId],enabled:Boolean(defect&&activityAllowed),queryFn:async()=>{const response=await request(`/defects/${encodeURIComponent(defectId)}/events`);if(!response.ok)throw new Error(await responseErrorMessage(response,"Recorded activity is not available to this account."));return response.json();},retry:false});
  const attachmentsAllowed=Boolean(identity&&(hasPermission(identity.permissions,"REPORT")||hasPermission(identity.permissions,"PERFORM_ACTION")||hasPermission(identity.permissions,"REVIEW")||hasPermission(identity.permissions,"APPROVE")||hasPermission(identity.permissions,"DEFECT_VIEW_ALL")));
  const attachmentsQuery=useQuery<Attachment[]>({queryKey:["defect-attachments",defectId],enabled:Boolean(defect&&attachmentsAllowed),queryFn:async()=>{const response=await request(`/defects/${encodeURIComponent(defectId)}/attachments`);if(!response.ok)throw new Error(await responseErrorMessage(response,"Evidence is not available to this account."));return response.json();},retry:false});
  const currentPurpose=defect&&identity?formPurposeForStatus(defect.status,identity.permissions):null;
  const areaName=defect?human(defect.location):"";
  const isGalley=defect?.location.toUpperCase()==="GALLEY";

  useEffect(()=>{const url=attachmentPreview?.url;return()=>{if(url)URL.revokeObjectURL(url);};},[attachmentPreview?.url]);

  useEffect(()=>{const previousTitle=document.title;const applicationName=identity?.applicationName?.trim()||"eCabin Ledger";document.title=defect?`${defect.reference} · Defect Details | ${applicationName}`:`Defect Details | ${applicationName}`;return()=>{document.title=previousTitle;};},[defect,identity?.applicationName]);

  async function viewAttachment(file:Attachment) {
    if(file.scanStatus==="REJECTED")return;
    setAttachmentPreview({file});
    setPreviewLoading(true);
    try{const response=await request(`/attachments/${encodeURIComponent(file.id)}/download`);if(!response.ok)throw new Error(await responseErrorMessage(response,"Could not open this attachment."));const url=URL.createObjectURL(await response.blob());setAttachmentPreview(current=>current?.file.id===file.id?{file,url}:current);}
    catch(error){setAttachmentPreview(current=>current?.file.id===file.id?{file,error:error instanceof Error?error.message:"Could not open this attachment."}:current);}
    finally{setPreviewLoading(false);}
  }

  async function downloadAttachment(file:Attachment) {
    if(file.scanStatus==="REJECTED"||downloadingAttachmentId)return;
    setDownloadingAttachmentId(file.id);
    try{const response=await request(`/attachments/${encodeURIComponent(file.id)}/download`);if(!response.ok)throw new Error(await responseErrorMessage(response,"Could not download this attachment."));const url=URL.createObjectURL(await response.blob());const anchor=document.createElement("a");anchor.href=url;anchor.download=file.filename;document.body.appendChild(anchor);anchor.click();anchor.remove();window.setTimeout(()=>URL.revokeObjectURL(url),60_000);}
    catch(error){await alert.error(error instanceof Error?error.message:"Could not download this attachment.");}
    finally{setDownloadingAttachmentId(null);}
  }

  async function onDecisionComplete() {
    await refreshDetail();
  }

  async function refreshDetail(){await Promise.all([queryClient.invalidateQueries({queryKey:["defect-details",defectId]}),queryClient.invalidateQueries({queryKey:["defect-activity",defectId]}),queryClient.invalidateQueries({queryKey:["defect-attachments",defectId]})]);window.dispatchEvent(new Event("review-queue-refresh"));window.dispatchEvent(new Event("maintenance-queue-refresh"));}

  if(identityQuery.isPending||defectQuery.isPending)return <main className="min-h-[100svh] bg-[#eaf1ff] p-5 text-[#000000]"><div className="mx-auto max-w-7xl rounded-2xl border border-[#e0e8e4] bg-white p-8" role="status">Loading defect details…</div></main>;
  if(identityQuery.isError&&identityQuery.error instanceof Error&&/401|session|sign in/i.test(identityQuery.error.message)){clearLocalSession();if(typeof window!=="undefined")window.location.replace(`${applicationBasePath}/login`);}
  if(identityQuery.isError)return <AccessStatePage applicationName={identityQuery.data?.applicationName} message={accessFailureMessage(identityQuery.error)} actionLabel="Return to sign in" onAction={()=>window.location.replace(`${applicationBasePath}/login`)}/>;
  if(defectQuery.isError)return <StatePage title="Defect unavailable" message={defectQuery.error.message} action="Back to Defect List" onAction={()=>onBack?onBack():router.push(returnPath)}/>;
  if(!defect||!identity)return null;

  const tabs=[{id:"overview",label:"Overview"},{id:"workflow",label:"Update Case"},{id:"attachments",label:"Attachments"},{id:"history",label:"History"}];
  const canReview=hasPermission(identity.permissions,"REVIEW");
  const showReassignTeam=canReassignMaintenance(identity.permissions,defect.activeActionId,defect.status);
  const canWorkflow=currentPurpose!==null;
  return <main className="min-h-[100svh] min-w-0 overflow-x-clip bg-[#eaf1ff] px-[clamp(14px,2.5vw,40px)] py-[clamp(16px,2.5vw,32px)] text-[#000000]">
    <div className="mx-auto grid w-full max-w-[1600px] min-w-0 gap-5">
      <header className="grid min-w-0 gap-4 rounded-2xl border border-[#e0e8e4] bg-white p-[clamp(18px,2.2vw,30px)] shadow-sm">
        <div className="flex min-w-0 flex-wrap items-start justify-between gap-4"><div className="min-w-0"><button type="button" onClick={()=>onBack?onBack():router.push(returnPath)} className="mb-4 inline-flex items-center gap-2 rounded-lg border border-[#dce5df] px-3 py-2 text-sm font-semibold text-[#476152] hover:bg-[#f5f8f6] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#3165bd]">← Back to Defect List</button><p className="m-0 text-[11px] font-bold tracking-[.15em] text-[#000000]">DEFECT DETAILS</p><h1 className="mb-0 mt-1 break-all text-[clamp(22px,3vw,32px)] font-bold text-[#000000]">{defect.reference}</h1></div><IssueMetadata severityCode={defect.severity} statusCode={defect.status} request={request} statuses={workflowStatuses}/></div>
        <dl className="m-0 grid min-w-0 grid-cols-2 gap-2 border-t border-[#edf1ed] pt-3 sm:grid-cols-5">{[["Aircraft registration",defect.tailNumber],["Aircraft type",defect.aircraftType],["Cabin area",areaName],["Zone / location",defect.zone||"Not recorded"],["Reported",formatDateTime(defect.reportedAt)]].map(([label,value])=><div className="min-w-0 rounded-lg bg-[#f5f8f6] p-3" key={label}><dt className="text-xs text-[#000000]">{label}</dt><dd className="m-0 mt-1 break-words text-sm font-semibold text-[#000000]">{value}</dd></div>)}</dl>
        {defect.routingAttention&&<p role="status" className="m-0 rounded-lg border border-[#f0d7ad] bg-[#fff9ed] p-3 text-sm text-[#795d2c]">{defect.routingAttention}</p>}
        {hasPermission(identity.permissions,"DEFECT_VIEW_ALL")&&!hasPermission(identity.permissions,"REVIEW")&&["REPORTED","UNDER_REVIEW"].includes(defect.status)&&<p role="status" className="m-0 rounded-lg border border-[#d8e4f4] bg-[#f2f7ff] p-3 text-sm text-[#455c7a]">Read-only access — this defect is awaiting Reviewer approval. Viewing permission does not allow decisions or workflow changes.</p>}
        {showReassignTeam&&<div className="flex flex-wrap items-center gap-2 border-t border-[#edf1ed] pt-3"><ReassignTeamButton onClick={()=>setReassignmentOpen(true)}/></div>}
      </header>
      <div className="grid min-w-0 items-start gap-5 lg:grid-cols-[minmax(0,2fr)_minmax(290px,.9fr)]">
        <section className="min-w-0 rounded-2xl border border-[#e0e8e4] bg-white p-[clamp(14px,2vw,24px)] shadow-sm">
          <div role="tablist" aria-label="Defect details sections" className="mb-5 flex w-fit max-w-full flex-wrap gap-1 rounded-xl border border-[#e5ebe7] p-1">{tabs.map(item=><button key={item.id} type="button" role="tab" id={`defect-tab-${item.id}`} aria-controls={`defect-panel-${item.id}`} aria-selected={tab===item.id} onClick={()=>{if(onBack){setEmbeddedTab(item.id);return;}const next=new URL(window.location.href);next.searchParams.set("tab",item.id);const browserPath=next.pathname;const routerPath=applicationBasePath&&browserPath.startsWith(`${applicationBasePath}/`)?browserPath.slice(applicationBasePath.length):browserPath;router.replace(`${routerPath}${next.search}`,{scroll:false});}} className={`case-detail-tab min-h-10 rounded-lg px-4 py-2 text-sm font-semibold transition-colors duration-150 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#3165bd] ${tab===item.id?"case-detail-tab-active bg-white text-[#a6472d] shadow-sm ring-1 ring-[#e2e8e3]":"text-[#000000] hover:bg-white/70 hover:text-[#000000]"}`}><span>{item.label}</span></button>)}</div>
          <section role="tabpanel" id={`defect-panel-${tab}`} aria-labelledby={`defect-tab-${tab}`} className="min-w-0">
            {tab==="overview"&&<section><h2 className="mt-0 text-lg font-bold">Original defect report</h2><p className="whitespace-pre-wrap break-words text-sm leading-6 text-[#000000]">{defect.description||"No description recorded."}</p><dl className="m-0 grid grid-cols-1 gap-3 sm:grid-cols-2">{[["Condition",defect.conditionName||"Not recorded"],["Category",defect.category||"Not recorded"],["Row",defect.rowNumber==null?"Not recorded":String(defect.rowNumber)],["Seat / reference",defect.seatReference||"Not recorded"],["Reporter",defect.reportedBy],["Assigned team / person",defect.assignedTeam||defect.assignedTo||"Unassigned"],["Last updated",formatDateTime(defect.updatedAt)],["Due date",defect.dueAt?formatDateTime(defect.dueAt):"Not set"]].map(([label,value])=><div className="min-w-0 rounded-lg bg-[#f7faf8] p-3" key={label}><dt className="text-xs text-[#000000]">{label}</dt><dd className="m-0 mt-1 break-words text-sm font-semibold">{value}</dd></div>)}</dl></section>}
            {tab==="workflow"&&<div className="grid min-w-0 gap-4">{isGalley&&canReview&&["REPORTED","UNDER_REVIEW"].includes(defect.status)&&<GalleyReviewerDecisionForm request={request} defect={defect} onComplete={()=>void onDecisionComplete()}/ >}{currentPurpose==="DEFECT_REPORT"&&defect.status==="REWORK"&&canWorkflow?<OperationalFormRenderer key={`${defect.id}:DEFECT_REPORT:${defect.updatedAt}`} request={request} defectId={defect.id} areaId={defect.areaId} areaName={areaName} areaCode={defect.location} purpose="DEFECT_REPORT" onWorkflowComplete={()=>void refreshDetail()}/>:currentPurpose&&currentPurpose!=="DEFECT_REPORT"&&canWorkflow?<OperationalFormRenderer key={`${defect.id}:${currentPurpose}:${defect.activeActionId||""}`} request={request} defectId={defect.id} actionId={defect.activeActionId||undefined} areaId={defect.areaId} areaName={areaName} areaCode={defect.location} purpose={currentPurpose} onWorkflowComplete={()=>void refreshDetail()}/>:currentPurpose==="DEFECT_REPORT"?null:!isGalley||!canReview||!["REPORTED","UNDER_REVIEW"].includes(defect.status)?<p className="rounded-lg bg-[#f7faf8] p-4 text-sm text-[#000000]">No editable workflow form is available to your permissions at the current status. The server remains authoritative for all workflow actions.</p>:null}</div>}
            {tab==="attachments"&&<div className="grid min-w-0 gap-3"><div><h2 className="m-0 text-lg font-bold">Supporting documents</h2><p className="mb-0 mt-1 text-sm text-[#000000]">Evidence is available through the authenticated attachment endpoint.</p></div>{!attachmentsAllowed?<p className="rounded-lg bg-[#f7faf8] p-4 text-sm text-[#000000]">Evidence is available to the reporting Auditor for their own case, assigned Maintenance team members, Reviewers, and authorized closure teams.</p>:attachmentsQuery.isPending?<p role="status">Loading authorized attachments…</p>:attachmentsQuery.isError?<div role="alert" className="text-sm text-[#9a4035]"><p>{attachmentsQuery.error.message}</p><button type="button" onClick={()=>void attachmentsQuery.refetch()} className="rounded-lg border px-3 py-2">Retry</button></div>:!attachmentsQuery.data?.length?<p className="rounded-lg bg-[#f7faf8] p-4 text-sm text-[#000000]">No supporting documents are attached to this defect.</p>:<ul className="m-0 grid min-w-0 list-none gap-2 p-0">{attachmentsQuery.data.map(file=><li className="flex min-w-0 flex-wrap items-center justify-between gap-3 rounded-xl border border-[#e3eae5] p-3" key={file.id}><span className="grid min-w-0 gap-1"><small className="text-xs font-semibold text-[#315b43]">Case evidence</small><strong className="break-all text-sm">{file.filename}</strong><small className="break-words text-xs text-[#000000]">{attachmentDescription(file.source)}</small><small className="break-words text-xs text-[#000000]">Uploaded by {file.uploadedBy||"uploader unavailable"} · {formatDateTime(file.uploadedAt)}</small><small className="break-all text-xs text-[#000000]">{file.fieldKey||"evidence"} · {(file.sizeBytes/1024/1024).toFixed(1)} MB</small></span><button type="button" disabled={file.scanStatus==="REJECTED"} onClick={()=>void viewAttachment(file)} className="rounded-lg border border-[#dce5df] px-3 py-2 text-sm font-semibold disabled:cursor-not-allowed disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#3165bd]">{file.scanStatus==="REJECTED"?"Unavailable":"View evidence"}</button></li>)}</ul>}</div>}
            {tab==="history"&&<ActivityTimeline events={eventsQuery.data||[]} loading={!activityAllowed?false:eventsQuery.isPending} error={!activityAllowed?"Activity history is available to assigned Maintenance members, Reviewers, and users with audit or tenant-wide view access.":eventsQuery.error instanceof Error?eventsQuery.error.message:undefined} onRetry={()=>void eventsQuery.refetch()} statuses={workflowStatuses} attachments={attachmentsQuery.data||[]} onOpenEvidence={id=>{const file=attachmentsQuery.data?.find(item=>item.id===id);if(file)void viewAttachment(file);}}/>}
          </section>
        </section>
        <aside className="min-w-0 lg:sticky lg:top-5"><ActivityTimeline events={eventsQuery.data||[]} loading={activityAllowed&&eventsQuery.isPending} error={!activityAllowed?"Activity history is available to assigned Maintenance members, Reviewers, and users with audit or tenant-wide view access.":eventsQuery.error instanceof Error?eventsQuery.error.message:undefined} onRetry={()=>void eventsQuery.refetch()} statuses={workflowStatuses} attachments={attachmentsQuery.data||[]} onOpenEvidence={id=>{const file=attachmentsQuery.data?.find(item=>item.id===id);if(file)void viewAttachment(file);}}/></aside>
      </div>
    </div>
    {attachmentPreview&&<AttachmentPreviewDialog file={attachmentPreview.file} url={attachmentPreview.url} loading={previewLoading} error={attachmentPreview.error} downloading={downloadingAttachmentId===attachmentPreview.file.id} onDownload={()=>void downloadAttachment(attachmentPreview.file)} onClose={()=>setAttachmentPreview(null)}/>}
    {reassignmentOpen&&defect.activeActionId&&<MaintenanceTeamReassignmentDialog defectId={defect.id} actionId={defect.activeActionId} currentTeam={defect.assignedTeam} request={request} onClose={()=>setReassignmentOpen(false)} onComplete={refreshDetail}/>}
  </main>;
}

function StatePage({title,message,action,onAction}:{title:string;message:string;action:string;onAction:()=>void}) { return <main className="grid min-h-[100svh] place-items-center bg-[#eaf1ff] p-5 text-[#000000]"><section className="w-full max-w-xl rounded-2xl border border-[#e0e8e4] bg-white p-7 shadow-sm"><p className="m-0 text-[11px] font-bold tracking-[.15em] text-[#000000]">ECABIN LEDGER</p><h1 className="mt-2 text-2xl font-bold">{title}</h1><p role="alert" className="text-sm leading-6 text-[#000000]">{message}</p><button type="button" onClick={onAction} className="mt-3 rounded-lg bg-[#426d55] px-4 py-2.5 text-sm font-semibold text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#3165bd]">{action}</button></section></main>; }
