"use client";

import { FormEvent, useMemo, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import OperationalFormRenderer from "@/common/forms/OperationalFormRenderer";
import { configuredAnswerIssue, ReportField } from "@/common/forms/defectReportMapping";
import { alert, responseErrorMessage } from "@/common/alerts";
import { areaFormSelection, selectableCabinAreas, singleAreaForm } from "@/common/fleet/areaFormSelection";
import { useDefectWorkflowStatuses, workflowStatusLabel } from "@/common/workflow/workflowStatuses";

type Request=(path:string,init?:RequestInit)=>Promise<Response>;
type Area={id:string;code:string;name:string;isactive?:number};
type Aircraft={id:string;tailNumber:string;msnNumber:string|null;aircraftType:string};
type Form={id:string;name:string;areaId:string;versionId:string;versionNumber:number;purpose:"DEFECT_REPORT";workflowStage:string;configuration:{fields:ReportField[]}};
type Created={id:string;reference:string};
type UploadField=ReportField&{fileRules?:{minCount?:number;maxCount?:number}};
function AreaIcon({code}:{code:string}){
  const common={fill:"none",stroke:"currentColor",strokeWidth:1.8,strokeLinecap:"round" as const,strokeLinejoin:"round" as const};
  if(code==="ATTENDANT_SEAT")return <svg viewBox="0 0 24 24" aria-hidden="true" {...common}><path d="M7 19v-8a3 3 0 0 1 6 0v3h4a2 2 0 0 1 0 4h-5a5 5 0 0 1-5-5M9 19v2m8-2v2"/></svg>;
  if(code==="CABIN")return <svg viewBox="0 0 24 24" aria-hidden="true" {...common}><path d="M4 13V7a2 2 0 0 1 4 0v4h5v5H4v-3Zm0 3v3m9-3v3m1-6V7a2 2 0 0 1 4 0v4h3v5h-7v-2Zm0 2v3m7-3v3"/></svg>;
  if(code==="ENTRY_AREA")return <svg viewBox="0 0 24 24" aria-hidden="true" {...common}><path d="M5 21V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v16M9 21V5m-6 16h18m-8-8h.01"/></svg>;
  if(code==="GALLEY")return <svg viewBox="0 0 24 24" aria-hidden="true" {...common}><path d="M5 6h14v13H5zM5 10h14M8 3h8M8 19v2m8-2v2"/><circle cx="8" cy="21" r="1"/><circle cx="16" cy="21" r="1"/></svg>;
  if(code==="LAVATORY")return <svg viewBox="0 0 24 24" aria-hidden="true" {...common}><circle cx="8" cy="5" r="2"/><path d="M8 8v7m-3 6v-5l3-3 3 3v5m5-12v8m-3 4v-6l3-3 3 3v6m-3-13a2 2 0 1 0 0-4 2 2 0 0 0 0 4Z"/></svg>;
  return <svg viewBox="0 0 24 24" aria-hidden="true" {...common}><circle cx="12" cy="12" r="8"/><path d="M12 8v8m-4-4h8"/></svg>;
}

export default function ReportDefectPage({request,onCreated,onOpenDefect,organizationCacheKey}:{request:Request;onCreated:(defect:Created)=>void;onOpenDefect:(defectId:string)=>void;organizationCacheKey:string}){
  const workflowStatuses = useDefectWorkflowStatuses(request).data?.items || [];
  const [areaId,setAreaId]=useState("");const [formKey,setFormKey]=useState("");const [answers,setAnswers]=useState<Record<string,unknown>>({});const [files,setFiles]=useState<Record<string,File[]>>({});const [aircraft,setAircraft]=useState<Aircraft|null>(null);const [ready,setReady]=useState(false);const [saving,setSaving]=useState(false);const [completed,setCompleted]=useState(false);const [statusMessage,setStatusMessage]=useState("");const [created,setCreated]=useState<Created|null>(null);const [resetKey,setResetKey]=useState(0);
  const idempotencyKey=useRef<string|null>(null);const uploaded=useRef<Record<string,string[]>>({});
  const areasQuery=useQuery<Area[]>({queryKey:["report-defect-cabin-areas",organizationCacheKey],queryFn:async({signal})=>{const response=await request("/reference-data",{signal});if(!response.ok)throw new Error(await responseErrorMessage(response,"Could not load active cabin areas."));const value=await response.json() as {areas?:Area[]};return (value.areas||[]).filter(area=>area.isactive===undefined||area.isactive===1);},staleTime:30000});
  const formsQuery=useQuery<Form[]>({queryKey:["report-defect-forms",organizationCacheKey,areaId],enabled:Boolean(areaId),queryFn:async({signal})=>{const response=await request(`/areas/${encodeURIComponent(areaId)}/forms?purpose=DEFECT_REPORT&stage=INITIAL_REPORT`,{signal});if(!response.ok)throw new Error(await responseErrorMessage(response,"Could not load published forms for this area."));return response.json();}});
  const forms=formsQuery.data||[];const sole=singleAreaForm(forms);const selectedForm=forms.find(form=>form.id===formKey)||(forms.length===1?sole:undefined);
  const area=useMemo(()=>areasQuery.data?.find(option=>option.id===areaId),[areasQuery.data,areaId]);
  function clearReportState(){setFormKey("");setAnswers({});setFiles({});setAircraft(null);setReady(false);setCompleted(false);setCreated(null);setStatusMessage("");idempotencyKey.current=null;uploaded.current={};setResetKey(value=>value+1);}
  function chooseArea(next:string){if(next===areaId)return;setAreaId(next);clearReportState();}
  function handleAnswerChange(next:Record<string,unknown>){if(!created)setAnswers(next);}
  function handleFilesChange(next:Record<string,File[]>){if(!created)setFiles(next);}
  function handleAircraftChange(next:Aircraft|null){if(!created)setAircraft(next);}
  function startAnotherReport(){setAnswers({});setFiles({});setAircraft(null);setReady(false);setCompleted(false);setCreated(null);setStatusMessage("");idempotencyKey.current=null;uploaded.current={};setResetKey(value=>value+1);}

  async function submit(event:FormEvent<HTMLFormElement>){event.preventDefault();if(saving||!selectedForm||!area||!aircraft||!ready)return;setSaving(true);setStatusMessage("");
    try{
      const schemaIssue=configuredAnswerIssue(selectedForm.configuration.fields,{...answers,...files});
      if(schemaIssue)throw new Error(schemaIssue);
      for(const field of selectedForm.configuration.fields as UploadField[]){if(!field.type.includes("UPLOAD"))continue;const count=(files[field.key]||[]).length;const rules=field.fileRules;if(rules?.maxCount!==undefined&&count>rules.maxCount)throw new Error(`Select no more than ${rules.maxCount} files for ${field.label}.`);}
      idempotencyKey.current ||= crypto.randomUUID();
      let defect=created;
      if(!defect){const response=await request("/defects",{method:"POST",headers:{"content-type":"application/json","Idempotency-Key":idempotencyKey.current},body:JSON.stringify({tailNumber:aircraft.tailNumber,aircraftId:aircraft.id,areaId,formId:selectedForm.id,formVersionId:selectedForm.versionId,formAnswers:answers})});if(!response.ok)throw new Error(await responseErrorMessage(response,"Could not create the defect. Check the selected aircraft and required form fields."));defect=await response.json() as Created;setCreated(defect);}
      const submissionAnswers={...answers};
      for(const field of selectedForm.configuration.fields as UploadField[]){if(!field.type.includes("UPLOAD"))continue;const done=uploaded.current[field.key]||[];const filesForField=files[field.key]||[];const ids=[...done];for(const file of filesForField.slice(done.length)){const body=new FormData();body.append("file",file);const response=await request(`/defects/${defect.id}/forms/${selectedForm.id}/versions/${selectedForm.versionId}/fields/${encodeURIComponent(field.key)}/attachments`,{method:"POST",body});if(!response.ok)throw new Error(await responseErrorMessage(response,`Could not upload ${file.name}. The defect ${defect.reference} has been created; retry to continue.`));const attachment=await response.json() as {id:string;scanStatus:string};if(attachment.scanStatus==="REJECTED")throw new Error(`${file.name} is unavailable. Choose another file.`);ids.push(attachment.id);uploaded.current[field.key]=ids;}submissionAnswers[field.key]=ids;}
      const historyResponse=await request(`/defects/${defect.id}/form-submissions`);if(!historyResponse.ok)throw new Error(await responseErrorMessage(historyResponse,"Defect created; could not confirm its form submission. Retry to continue."));const history=await historyResponse.json() as Array<{formId:string;versionId:string}>;const alreadySubmitted=history.some(item=>item.formId===selectedForm.id&&item.versionId===selectedForm.versionId);
      if(!alreadySubmitted){const response=await request(`/defects/${defect.id}/form-submissions`,{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({formId:selectedForm.id,versionId:selectedForm.versionId,purpose:"DEFECT_REPORT",answers:submissionAnswers})});if(!response.ok)throw new Error(await responseErrorMessage(response,`Defect ${defect.reference} was created but the form submission needs recovery. Retry to continue.`));}
      setCreated(defect);setCompleted(true);setStatusMessage("");idempotencyKey.current=null;onCreated(defect);void alert.success(`Defect ${defect.reference} was reported successfully. Status: ${workflowStatusLabel("REPORTED", workflowStatuses)}.`);
    }catch(error){const message=error instanceof Error?error.message:"Could not submit this defect report.";setStatusMessage(message);void alert.error(message);}
    finally{setSaving(false);}
  }

  const availableAreas=selectableCabinAreas(areasQuery.data||[]);
  const activeStep=completed?4:!areaId?1:selectedForm&&aircraft?3:2;
  return <div className="content report-defect-page grid gap-[18px]">
    <div className="heading"><div><h1>Report Defect</h1></div></div>
    <section className="report-defect-stepper-card" aria-label="Defect report progress"><ol className="report-defect-stepper" aria-label="Defect report steps">{["Choose cabin area","Select aircraft","Complete report"].map((label,index)=>{const step=index+1;const state=completed||step<activeStep?"complete":step===activeStep?"current":"upcoming";return <li key={label} data-state={state} aria-current={state==="current"?"step":undefined}><span className="report-defect-step-number">{state==="complete"?"✓":step}</span><span>{label}</span></li>;})}</ol></section>
    <section className="report-area-card" aria-labelledby="report-area-heading"><div className="report-area-card-heading"><h2 id="report-area-heading">Choose cabin area</h2><p>Select the cabin area where the defect was found.</p></div>
      {areasQuery.isPending?<p role="status" className="aircraft-area-message">Loading active cabin areas…</p>:areasQuery.isError?<p role="alert" className="aircraft-area-message error">{areasQuery.error.message} <button className="button-secondary" type="button" onClick={()=>void areasQuery.refetch()}>Retry</button></p>:availableAreas.length?<div className="report-area-options" role="group" aria-label="Cabin areas">{availableAreas.map(item=><button type="button" key={item.id} className={item.id===areaId?"report-area-option selected":"report-area-option"} aria-pressed={item.id===areaId} disabled={saving||Boolean(created)} onClick={()=>chooseArea(item.id!)}><span className="report-area-icon"><AreaIcon code={item.code}/></span><span className="report-area-name">{item.name}</span>{item.id===areaId&&<span className="report-area-selected" aria-label="Selected">✓</span>}</button>)}</div>:<p role="status" className="aircraft-area-message">No active cabin areas are configured. Ask an organization administrator to activate an area.</p>}
      {areaId&&formsQuery.isPending&&<p role="status" className="aircraft-area-message">Loading published reporting forms…</p>}
      {areaId&&formsQuery.isError&&<p role="alert" className="aircraft-area-message error">{formsQuery.error.message} <button className="button-secondary" type="button" onClick={()=>void formsQuery.refetch()}>Retry</button></p>}
      {formsQuery.isSuccess&&forms.length===0&&<p role="status" className="aircraft-area-message">No published defect-report form is configured for {area?.name}. Ask an administrator to create or publish an INITIAL_REPORT form in Form Master.</p>}
      {formsQuery.isSuccess&&areaFormSelection(forms)==="multiple"&&<div className="aircraft-form-choices grid grid-cols-[repeat(auto-fit,minmax(min(100%,220px),1fr))] items-stretch gap-[9px] pt-[7px]" role="group" aria-label="Published forms">{formKey&&<button className="button-secondary" type="button" disabled={Boolean(created)} onClick={()=>{setFormKey("");clearReportState();}}>Change form</button>}<strong>Choose a published form</strong>{forms.map(form=><button className="aircraft-form-choice grid gap-[5px]" type="button" key={`${form.id}:${form.versionId}`} disabled={Boolean(created)} aria-pressed={selectedForm?.versionId===form.versionId} onClick={()=>{setFormKey(form.id);setAnswers({});setFiles({});setAircraft(null);setReady(false);setCompleted(false);setCreated(null);setStatusMessage("");uploaded.current={};idempotencyKey.current=null;setResetKey(value=>value+1);}}><span>{form.name}</span><small>Published version {form.versionNumber}</small></button>)}</div>}
      {areasQuery.isSuccess&&availableAreas.length>0&&<div className="report-area-next"><button type="button" disabled={!areaId||!selectedForm} onClick={()=>document.getElementById("defect-report-form")?.scrollIntoView({behavior:"smooth",block:"start"})}><span>{!areaId?"Choose an area":formsQuery.isPending?"Loading published report":formsQuery.isError||!forms.length?"Published report unavailable":areaFormSelection(forms)==="multiple"&&!selectedForm?"Choose a published form":"Select an aircraft"}</span><span className="report-area-next-arrow" aria-hidden="true">→</span></button></div>}
    </section>
    {selectedForm&&area&&<section className="configuration-table-card report-defect-form-card"><form id="defect-report-form" onSubmit={submit}><fieldset disabled={saving||Boolean(created)} className="report-defect-fieldset"><OperationalFormRenderer key={`${resetKey}:${area.id}:${selectedForm.id}:${selectedForm.versionId}`} request={request} areaId={area.id} areaName={area.name} areaCode={area.code} initialFormId={selectedForm.id} initialVersionId={selectedForm.versionId} preparationMode initialAnswers={answers} initialFiles={files} onAnswersChange={handleAnswerChange} onFilesChange={handleFilesChange} onAircraftSelected={handleAircraftChange} onPreparationReady={setReady} aircraftOptionsCacheKey={organizationCacheKey}/></fieldset><div className="modal-actions flex justify-end gap-[9px] flex-wrap border-t border-[#edf1f6] pt-[14px] mt-[18px]"><button className="button-primary" type="submit" disabled={saving||completed||!ready||!aircraft}>{saving?"Submitting…":completed?"Report submitted":"Submit defect report →"}{!saving&&completed&&" ✓"}</button>{created&&<button className="button-secondary" type="button" onClick={()=>onOpenDefect(created.id)}>Open defect {created.reference}</button>}{completed&&<button className="button-secondary" type="button" onClick={startAnotherReport}>Report another defect</button>}</div></form></section>}
    {statusMessage&&<div className="report-defect-error" role="alert"><span>{statusMessage}</span></div>}
  </div>;
}
