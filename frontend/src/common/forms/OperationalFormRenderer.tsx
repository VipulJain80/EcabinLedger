"use client";

import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { alert, responseErrorMessage } from "@/common/alerts";
import { formatDateTime } from "@/common/dateTime";
import { isPublishedFormForArea, orderedOperationalFields, unsupportedPublishedField } from "./publishedFormDefinition";
import { defectReportBindingIssue } from "./defectReportMapping";
import EvidenceUploadField from "./EvidenceUploadField";
import { maxTotalBytesForField, partitionEvidenceSelection, validateEvidenceFile } from "./evidenceFileRules";
import { isFieldRequired, isFieldVisible } from "./conditionalFieldVisibility";
import AttachmentPreviewDialog from "@/common/dialogs/AttachmentPreviewDialog";
import AircraftRegistrationSelect from "./AircraftRegistrationSelect";
import { SearchableMultiSelect, SearchableSelect } from "@/common/selects/SearchableSelect";
import { shouldUseSearchableSelect } from "@/common/selects/searchableDropdownModel.mjs";

type Request = (path: string, init?: RequestInit) => Promise<Response>;
type Field = {
  key: string;
  label: string;
  type: string;
  helpText?: string | null;
  required: boolean;
  order: number;
  visible?: boolean | null;
  visibleWhen?: { fieldKey: string; equals: string } | null;
  requiredWhen?: { fieldKey: string; equals: string } | null;
  readOnly?: boolean | null;
  optionSource?: string | null;
  optionFilter?: { areaFieldKey?: string | null; componentFieldKey?: string | null } | null;
  fileRules?: { extensions: string[]; mimeTypes: string[]; maxFileSizeBytes: number; minCount: number; maxCount: number; maxTotalSizeBytes?: number | null } | null;
  textRules?: { minLength?: number | null; maxLength?: number | null; alphaOnly?: boolean; alphanumeric?: boolean; pattern?: string | null } | null;
  numericRules?: { min?: number | null; max?: number | null; precision?: number | null } | null;
  dateRules?: { minDate?: string | null; maxDate?: string | null } | null;
  binding?: import("./defectReportMapping").DefectBinding | null;
};
type Purpose = "DEFECT_REPORT" | "INSPECTION" | "CORRECTIVE_ACTION" | "VERIFICATION" | "CLOSURE_APPROVAL" | "REOPEN";
type Definition = { id: string; name: string; areaId: string; purpose: Purpose; workflowStage: string; versionId: string; versionNumber: number; configuration: { fields: Field[] } };
type Choice = { id: string; label: string; code: string };
type AircraftOption = { id:string; tailNumber:string; msnNumber:string|null; aircraftType:string };
type Uploaded = { id: string; filename: string; sizeBytes?: number; scanStatus: string };
type CaseEvidence = { id:string;formSubmissionId:string|null;fieldKey:string;filename:string;mimeType:string;sizeBytes:number;scanStatus:string;uploadedAt:string;source?:string|null;uploadedBy?:string|null };
type EvidencePreview = { file:CaseEvidence; url?:string; error?:string };

function evidenceSourceLabel(source?:string|null){
  if(source==="DEFECT_REPORT")return "Auditor report";
  if(source==="CORRECTIVE_ACTION")return "Maintenance action";
  return source?source.replaceAll("_"," "):"Case evidence · source unavailable";
}

function fileIdentity(file: File) { return `${file.name}:${file.size}:${file.lastModified}`; }
const EMPTY_FILES: File[] = [];

export default function OperationalFormRenderer({ request, defectId, actionId, areaId, areaName, areaCode, initialFormId, initialVersionId, purpose = "DEFECT_REPORT", workflowStage = "INITIAL_REPORT", previewOnly = false, preparationMode = false, initialAnswers, initialFiles, onAnswersChange, onFilesChange, onPreparationReady, onAircraftSelected, onWorkflowComplete, aircraftOptionsCacheKey = "" }: {
  request: Request;
  defectId?: string;
  actionId?: string;
  areaId: string;
  areaName?: string;
  areaCode?: string;
  initialFormId?: string;
  initialVersionId?: string;
  purpose?: Purpose;
  workflowStage?: string;
  previewOnly?: boolean;
  preparationMode?: boolean;
  initialAnswers?: Record<string, unknown>;
  initialFiles?: Record<string, File[]>;
  onAnswersChange?: (answers: Record<string, unknown>) => void;
  onFilesChange?: (files: Record<string, File[]>) => void;
  onPreparationReady?: (ready: boolean) => void;
  onAircraftSelected?: (aircraft: AircraftOption | null) => void;
  onWorkflowComplete?: (status: string) => void;
  aircraftOptionsCacheKey?: string;
}) {
  const [formId, setFormId] = useState(initialFormId || "");
  const [values, setValues] = useState<Record<string, unknown>>(initialAnswers || {});
  const [files, setFiles] = useState<Record<string, Uploaded[]>>({});
  const [pendingFiles, setPendingFiles] = useState<Record<string, File[]>>(initialFiles || {});
  const [uploadErrors, setUploadErrors] = useState<Record<string, Record<string, string>>>({});
  const [uploading, setUploading] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState("");
  const [successMessage, setSuccessMessage] = useState("");
  const [evidencePreview, setEvidencePreview] = useState<EvidencePreview|null>(null);
  const [reportSubmitted, setReportSubmitted] = useState(false);
  const [options, setOptions] = useState<Record<string, Choice[]>>({});
  const [selectedAircraft,setSelectedAircraft]=useState<AircraftOption|null>(null);
  const submissionGuard = useRef(false);
  const initialUploadsStarted = useRef(false);
  const previousSelectionContext = useRef<string | null>(null);

  const available = useQuery<Definition[]>({
    queryKey: ["area-operational-forms", areaId, defectId || "new", purpose, workflowStage],
    enabled: !initialFormId && Boolean(areaId),
    queryFn: async () => {
      const path = defectId ? `/defects/${encodeURIComponent(defectId)}/operational-forms?purpose=${encodeURIComponent(purpose)}` : `/areas/${encodeURIComponent(areaId)}/forms?purpose=${encodeURIComponent(purpose)}&stage=${encodeURIComponent(workflowStage)}`;
      const response = await request(path);
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load forms for this cabin area."));
      return response.json();
    },
  });
  const initialDefinition = useQuery<Definition>({
    queryKey: ["published-operational-form", areaId, initialFormId, initialVersionId],
    enabled: Boolean(initialFormId && initialVersionId),
    queryFn: async () => {
      const response = await request(`/forms/${encodeURIComponent(initialFormId! as string)}/versions/${encodeURIComponent(initialVersionId! as string)}/definition?areaId=${encodeURIComponent(areaId)}`);
      if (!response.ok) throw new Error(await responseErrorMessage(response, "The selected published form version is no longer available."));
      return response.json();
    },
  });

  const availableForms = available.data || [];
  const selectedFormId = initialFormId || formId || (availableForms.length === 1 ? availableForms[0].id : "");
  const selected = reportSubmitted && purpose === "DEFECT_REPORT" ? undefined : initialFormId ? initialDefinition.data : availableForms.find(form => form.id === selectedFormId);
  const selectionContext = `${areaId}:${defectId || "new"}:${purpose}:${workflowStage}:${selectedFormId}:${selected?.versionId || ""}`;
  const selectedFields = useMemo(() => orderedOperationalFields(selected?.configuration.fields || []).filter(field => isFieldVisible(field, values)), [selected, values]);
  const unsupportedField = unsupportedPublishedField(selectedFields);
  const maintenanceStatusField=selectedFields.find(field=>field.binding==="MAINTENANCE_STATUS");
  const maintenanceStatusChoices=maintenanceStatusField?options[`${selected?.versionId}:${maintenanceStatusField.key}:${areaId}:` ]||[]:[];
  const selectedMaintenanceStatus=maintenanceStatusChoices.find(choice=>choice.id===String(values[maintenanceStatusField?.key||""]||""))?.code||"";
  const slaPolicyQuery=useQuery<{policy:{dateRequired:boolean;businessTimezone:string};today:string;minDate:string;maxDate:string}>({
    queryKey:["maintenance-sla-effective-policy",defectId,actionId,selectedMaintenanceStatus],
    enabled:purpose==="CORRECTIVE_ACTION"&&Boolean(defectId&&selectedMaintenanceStatus),
    queryFn:async({signal})=>{const response=await request(`/defects/${encodeURIComponent(defectId!)}/maintenance-sla-policy?status=${encodeURIComponent(selectedMaintenanceStatus)}`,{signal});if(!response.ok)throw new Error(await responseErrorMessage(response,"Could not load the effective SLA date policy."));return response.json();},
    retry:false,
  });
  const reportBindingIssue = preparationMode && selected ? defectReportBindingIssue(selectedFields, areaCode) : "";
  const definitionIssue = selected && !isPublishedFormForArea(selected, areaId)
    ? "The published form does not belong to the selected cabin area. Contact your administrator."
    : unsupportedField ? `Published form field “${unsupportedField.label}” uses unsupported type “${unsupportedField.type}”. Contact your administrator.` : "";
  useEffect(() => {
    if (preparationMode) onPreparationReady?.(Boolean(initialDefinition.isSuccess && selected && !definitionIssue && !reportBindingIssue));
  }, [definitionIssue, initialDefinition.isSuccess, onPreparationReady, preparationMode, reportBindingIssue, selected]);
  const closureEvidence = useQuery<CaseEvidence[]>({
    queryKey:["closure-review-evidence",defectId],
    enabled:Boolean(defectId&&purpose==="CLOSURE_APPROVAL"&&!previewOnly),
    queryFn:async({signal})=>{const response=await request(`/defects/${encodeURIComponent(defectId!)}/attachments`,{signal});if(!response.ok)throw new Error(await responseErrorMessage(response,"Could not load authorized case evidence."));return response.json();},
  });

  useEffect(()=>{const url=evidencePreview?.url;return()=>{if(url)URL.revokeObjectURL(url);};},[evidencePreview?.url]);

  async function viewCaseEvidence(item:CaseEvidence){
    if(item.scanStatus==="REJECTED")return;
    setEvidencePreview({file:item});
    try{const response=await request(`/attachments/${encodeURIComponent(item.id)}/download`);if(!response.ok)throw new Error(await responseErrorMessage(response,"Could not open this evidence."));const url=URL.createObjectURL(await response.blob());setEvidencePreview(current=>{if(current?.file.id!==item.id){URL.revokeObjectURL(url);return current;}return{file:item,url};});}
    catch(error){setEvidencePreview(current=>current?.file.id===item.id?{file:item,error:error instanceof Error?error.message:"Could not open this evidence."}:current);}
  }

  function downloadCaseEvidence(){
    if(!evidencePreview?.url)return;
    const anchor=document.createElement("a");anchor.href=evidencePreview.url;anchor.download=evidencePreview.file.filename;document.body.appendChild(anchor);anchor.click();anchor.remove();
  }

  useEffect(() => {
    let alive = true;
    if (!selected) return;
    for (const field of selectedFields) {
      if (!field.optionSource) continue;
      const areaKey = field.optionFilter?.areaFieldKey;
      const componentKey = field.optionFilter?.componentFieldKey;
      const chosenArea = areaKey ? String(values[areaKey] || "") : areaId;
      const componentId = componentKey ? String(values[componentKey] || "") : "";
      const cacheKey = `${selected.versionId}:${field.key}:${chosenArea}:${componentId}`;
      if (options[cacheKey]) continue;
      if ((areaKey && !chosenArea) || (componentKey && !componentId)) continue;
      const params = new URLSearchParams();
      if (chosenArea) params.set("areaId", chosenArea);
      if (componentId) params.set("componentId", componentId);
      void request(`/forms/${selected.id}/versions/${selected.versionId}/fields/${encodeURIComponent(field.key)}/options?${params}`)
        .then(async response => {
          if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load authorized field options."));
          const data = await response.json() as Choice[];
          if (alive) setOptions(current => ({ ...current, [cacheKey]: data }));
        })
        .catch(error => { if (alive) setMessage(error instanceof Error ? error.message : "Could not load options."); });
    }
    return () => { alive = false; };
  }, [selected, selectedFields, request, values, options, areaId]);

  useEffect(() => {
    if (!selected) return;
    if (previousSelectionContext.current !== null && previousSelectionContext.current !== selectionContext) {
      setValues(initialAnswers || {}); setFiles({}); setPendingFiles(initialFiles || {}); setUploadErrors({}); setOptions({}); setMessage("");
      setSelectedAircraft(null);onAircraftSelected?.(null);
      submissionGuard.current = false; initialUploadsStarted.current = false;
    }
    previousSelectionContext.current = selectionContext;
  }, [selectionContext, selected, initialAnswers, initialFiles, onAircraftSelected]);

  function changeValue(field: Field, value: unknown) {
    const next = { ...values, [field.key]: value };
    for (const dependent of selectedFields) {
      if (dependent.optionFilter?.areaFieldKey === field.key || dependent.optionFilter?.componentFieldKey === field.key) delete next[dependent.key];
    }
    setValues(next);
    if (preparationMode) onAnswersChange?.(next);
  }

  const upload = useCallback(async (field: Field, file: File, selectionAlreadyValidated = false): Promise<boolean> => {
    if (!selected || !defectId) return false;
    const validationIssue = validateEvidenceFile(file, field);
    const existingUploaded=(files[field.key]||[]).filter(item=>item.scanStatus!=="REJECTED");
    const otherPending=(pendingFiles[field.key]||[]).filter(item=>item!==file);
    const selectionIssue=!selectionAlreadyValidated?partitionEvidenceSelection([...existingUploaded,...otherPending], [file], field).rejected[0]?.message:null;
    if (validationIssue||selectionIssue) {
      const failure=validationIssue||selectionIssue!;
      setMessage(failure); void alert.error(failure);
      setUploadErrors(old=>({...old,[field.key]:{...(old[field.key]||{}),[fileIdentity(file)]:failure}}));
      return false;
    }
    setUploading(field.key); setMessage("");
    try {
      const body = new FormData(); body.append("file", file);
      const response = await request(`/defects/${defectId}/forms/${selected.id}/versions/${selected.versionId}/fields/${encodeURIComponent(field.key)}/attachments`, { method: "POST", body });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Evidence upload failed."));
      const uploaded = await response.json() as Uploaded;
      setFiles(old => ({ ...old, [field.key]: [...(old[field.key] || []), uploaded] }));
      setPendingFiles(old=>({...old,[field.key]:(old[field.key]||[]).filter(item=>item!==file)}));
      setUploadErrors(old=>{const next={...old,[field.key]:{...(old[field.key]||{})}};delete next[field.key][fileIdentity(file)];return next;});
      if (uploaded.scanStatus === "REJECTED") setMessage(`${uploaded.filename} is unavailable. Remove it and upload another file.`);
      return true;
    } catch (error) {
      const failure=error instanceof Error ? error.message : "Evidence upload failed.";
      setMessage(failure); void alert.error(failure);
      setPendingFiles(old=>({...old,[field.key]:(old[field.key]||[]).some(item=>item===file)?old[field.key]:[...(old[field.key]||[]),file]}));
      setUploadErrors(old=>({...old,[field.key]:{...(old[field.key]||{}),[fileIdentity(file)]:failure}}));
      return false;
    }
    finally { setUploading(null); }
  }, [defectId, files, pendingFiles, request, selected]);

  useEffect(() => {
    if (!defectId || !selected || !initialFiles || initialUploadsStarted.current) return;
    initialUploadsStarted.current = true;
    void (async () => {
      for (const field of selectedFields.filter(item => item.type.includes("UPLOAD"))) {
        for (const file of initialFiles[field.key] || []) await upload(field, file);
      }
    })();
  }, [defectId, initialFiles, selected, selectedFields, upload]);

  async function uploadMany(field: Field, incoming: File[]) {
    const rules = field.fileRules;
    if (!rules) return;
    const existing=pendingFiles[field.key]||[];
    const selectedUploaded=(files[field.key]||[]).filter(file=>file.scanStatus!=="REJECTED");
    const partition=partitionEvidenceSelection([...selectedUploaded,...existing],incoming,field);
    const valid=partition.accepted;
    const errors=partition.rejected.map(item=>item.message);
    if(errors.length){
      const rejectedErrors = Object.fromEntries(partition.rejected.map(item => [fileIdentity(item.file), item.message]));
      setUploadErrors(old => {
        const previous = old[field.key] || {};
        const retryable = Object.fromEntries(Object.entries(previous).filter(([key]) => existing.some(file => fileIdentity(file) === key)));
        return { ...old, [field.key]: { ...retryable, ...rejectedErrors } };
      });
      setMessage(errors.join(" ")); void alert.error(errors[0]);
    }
    if(!valid.length)return;
    if (!defectId && preparationMode) {
      const next={...pendingFiles,[field.key]:[...existing,...valid]};
      setPendingFiles(next);setUploadErrors(old=>{const nextErrors={...old,[field.key]:{...(old[field.key]||{})}};for(const file of valid)delete nextErrors[field.key][fileIdentity(file)];return nextErrors;});onFilesChange?.(next);
      if(!errors.length)setMessage("");return;
    }
    if(!defectId)return;
    setPendingFiles(old=>({...old,[field.key]:[...(old[field.key]||[]),...valid]}));
    for (const file of valid) await upload(field, file, true);
  }

  function removePendingFile(field:Field,file:File){
    const next={...pendingFiles,[field.key]:(pendingFiles[field.key]||[]).filter(item=>item!==file)};
    setPendingFiles(next);setUploadErrors(old=>{const nextErrors={...old,[field.key]:{...(old[field.key]||{})}};delete nextErrors[field.key][fileIdentity(file)];return nextErrors;});
    if(preparationMode)onFilesChange?.(next);
    setMessage("");
  }

  async function removeFile(field: Field, file: Uploaded) {
    if (!selected) return;
    const reason = await alert.prompt({ title: "Reason to remove uploaded evidence", required: true });
    if (!reason) return;
    try {
      const response = await request(`/attachments/${file.id}/remove`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ reason }) });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not remove evidence."));
      setFiles(current => ({ ...current, [field.key]: (current[field.key] || []).filter(item => item.id !== file.id) }));
    } catch (error) { await alert.error(error instanceof Error ? error.message : "Could not remove evidence."); }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selected || !defectId || submissionGuard.current || previewOnly) return;
    if(purpose==="CLOSURE_APPROVAL"){
      const decisionField=selectedFields.find(field=>field.binding==="CLOSURE_DECISION");
      const remarkField=selectedFields.find(field=>field.binding==="CLOSURE_REMARK");
      const decision=String(values[decisionField?.key||""]||"");
      const allowed=decisionField?options[`${selected.versionId}:${decisionField.key}:${areaId}:`]||[]:[];
      const remark=String(values[remarkField?.key||""]||"").trim();
      if(!allowed.some(option=>option.code===decision)){setMessage("Choose Accept or Decline.");return;}
      if(decision==="DECLINE"&&!remark){setMessage("Enter a reason before declining closure.");return;}
      if(remark.length>2000){setMessage("Remarks must be 2,000 characters or fewer.");return;}
      const accepted=await alert.confirm({title:decision==="ACCEPT"?"Accept closure?":"Decline closure?",text:decision==="ACCEPT"?"This will close the defect.":"This will return the defect to its assigned maintenance team for further work.",confirmText:decision==="ACCEPT"?"Accept and close":"Decline and return"});
      if(!accepted)return;
      submissionGuard.current=true;setSubmitting(true);setMessage("");setSuccessMessage("");
      try{
        const response=await request(`/defects/${encodeURIComponent(defectId)}/approvals`,{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({decision,reason:remark||null,formId:selected.id,versionId:selected.versionId})});
        if(!response.ok)throw new Error(await responseErrorMessage(response,"Closure decision could not be saved."));
        const result=await response.json() as {status:string};
        const confirmation=decision==="ACCEPT"?"Defect closed successfully.":"Defect returned to its assigned Maintenance Team.";
        setSuccessMessage(confirmation);void alert.success(confirmation);
        setValues({});await available.refetch();onWorkflowComplete?.(result.status);
      }catch(error){const failure=error instanceof Error?error.message:"Closure decision could not be saved.";setMessage(failure);void alert.error(failure);}
      finally{submissionGuard.current=false;setSubmitting(false);}
      return;
    }
    if(purpose==="CORRECTIVE_ACTION"){
      if(!actionId){setMessage("The active corrective action is unavailable. Refresh the case and try again.");return;}
      const statusField=selectedFields.find(field=>field.binding==="MAINTENANCE_STATUS");
      const dateField=selectedFields.find(field=>field.binding==="SLA_TARGET_DATE");
      const remarkField=selectedFields.find(field=>field.binding==="MAINTENANCE_REMARK");
      const statusCode=(statusField?options[`${selected.versionId}:${statusField.key}:${areaId}:`]:[])?.find(option=>option.id===String(values[statusField?.key||""]||""))?.code;
      if(!statusCode){setMessage("Choose a maintenance status.");return;}
      for(const evidenceField of selectedFields.filter(field=>field.type==="IMAGE_UPLOAD"||field.type==="FILE_UPLOAD")){
        const requiredWhen=evidenceField.requiredWhen;
        const conditionRequired=requiredWhen!==null&&requiredWhen!==undefined&&requiredWhen.fieldKey===statusField?.key&&requiredWhen.equals===statusCode;
        const minimum=evidenceField.required||conditionRequired?Math.max(1,evidenceField.fileRules?.minCount||0):evidenceField.fileRules?.minCount||0;
        const count=(files[evidenceField.key]||[]).filter(file=>file.scanStatus!=="REJECTED").length;
        if(count<minimum){const failure=`Attach at least ${minimum} image file(s) for ${evidenceField.label} before submitting ${statusCode.replaceAll("_"," ")}.`;setMessage(failure);void alert.error(failure);return;}
      }
      if(slaPolicyQuery.isPending||slaPolicyQuery.isError){setMessage(slaPolicyQuery.error?.message||"Wait for the SLA policy to load before submitting.");return;}
      const targetDate=String(values[dateField?.key||""]||"");
      if(slaPolicyQuery.data.policy.dateRequired&&!targetDate){setMessage("SLA Target Date is required.");return;}
      const remark=String(values[remarkField?.key||""]||"");
      submissionGuard.current=true;setSubmitting(true);setMessage("");setSuccessMessage("");
      const attachments=Object.fromEntries(selectedFields.filter(field=>field.type==="IMAGE_UPLOAD"||field.type==="FILE_UPLOAD").map(field=>[field.key,(files[field.key]||[]).filter(file=>file.scanStatus!=="REJECTED").map(file=>file.id)]));
      try{const response=await request(`/defects/${encodeURIComponent(defectId)}/actions/${encodeURIComponent(actionId)}/maintenance-form`,{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({formId:selected.id,formVersionId:selected.versionId,status:statusCode,slaTargetDate:targetDate||null,remark:remark||null,attachments})});if(!response.ok)throw new Error(await responseErrorMessage(response,"Maintenance update failed validation."));const confirmation="Maintenance action updated.";setSuccessMessage(confirmation);void alert.success(confirmation);setValues({});await available.refetch();onWorkflowComplete?.("");}
      catch(error){const failure=error instanceof Error?error.message:"Maintenance update failed.";setMessage(failure);void alert.error(failure);}
      finally{submissionGuard.current=false;setSubmitting(false);}
      return;
    }
    submissionGuard.current = true;
    const answers = { ...values };
    for (const field of selectedFields.filter(item => item.type.includes("UPLOAD"))) answers[field.key] = (files[field.key] || []).map(file => file.id);
    setSubmitting(true); setMessage("");setSuccessMessage("");
    try {
      const response = await request(`/defects/${defectId}/form-submissions`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ formId: selected.id, versionId: selected.versionId, purpose: selected.purpose, answers }) });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Form submission failed validation."));
      setSuccessMessage("Operational form submitted successfully.");
      void alert.success("Operational form submitted and added to the defect audit trail.");
      if (purpose === "DEFECT_REPORT") setReportSubmitted(true);
      setValues({}); setFiles({}); setPendingFiles({}); setUploadErrors({}); await available.refetch();onWorkflowComplete?.("");
    } catch (error) { const failure=error instanceof Error ? error.message : "Form submission failed.";setMessage(failure);void alert.error(failure); }
    finally { submissionGuard.current = false; setSubmitting(false); }
  }

  const requestPending = initialFormId ? initialDefinition.isPending : available.isPending;
  const requestError = initialFormId ? initialDefinition.error : available.error;
  const requestFailed = initialFormId ? initialDefinition.isError : available.isError;
  const noForms = (reportSubmitted && purpose === "DEFECT_REPORT") || (!initialFormId && available.isSuccess && availableForms.length === 0);

  if (defectId && purpose === "REOPEN" && noForms) return null;

  return <section className={`operational-form-panel my-5 p-[18px]${previewOnly || preparationMode ? " operational-form-report-context" : ""}`} aria-label={previewOnly ? "Selected published operational form preview" : preparationMode ? "Published defect report form" : "Area inspection form"}>
    {selected && <header className="operational-form-heading mb-3 flex items-start justify-between border-b border-[#edf1ed] pb-3"><div>{!preparationMode && !(defectId && !previewOnly) && <span className="eyebrow">PUBLISHED · VERSION {selected.versionNumber}</span>}<h3>{selected.name}</h3><p>Area: <b>{selected.areaId === areaId ? areaName || "Selected cabin area" : "Area configuration mismatch"}</b></p></div></header>}
    {requestPending && <p role="status">Loading the selected published form…</p>}
    {requestFailed && <p role="alert">{requestError instanceof Error ? requestError.message : "Could not load the selected published form."}</p>}
    {noForms && !(defectId && purpose === "DEFECT_REPORT") && <p role="status">{purpose === "DEFECT_REPORT" ? "No published operational form is configured for this cabin area. Please contact your administrator." : `No published ${purpose.replaceAll("_", " ").toLowerCase()} form is configured for this workflow stage. Please contact your administrator.`}</p>}
    {defectId&&purpose==="CLOSURE_APPROVAL"&&!previewOnly&&<section className="closure-review-evidence my-[14px] grid min-w-0 gap-[9px] rounded-[10px] border border-[#dce6df] bg-[#f8fbf9] p-[13px_15px]" aria-labelledby="closure-review-evidence-title"><h4 id="closure-review-evidence-title" className="m-0">Case evidence</h4>{closureEvidence.isPending?<p className="m-0" role="status">Loading authorized evidence…</p>:closureEvidence.isError?<p className="m-0" role="alert">{closureEvidence.error.message} <button type="button" className="button-secondary" onClick={()=>void closureEvidence.refetch()}>Retry</button></p>:!closureEvidence.data?.length?<p className="m-0">No evidence is attached to this case.</p>:<ul className="m-0 grid list-none gap-2 p-0">{closureEvidence.data.map(item=><li className="flex min-w-0 flex-wrap items-center justify-between gap-3 rounded-lg border border-[#e2e9e4] bg-white px-[10px] py-2" key={item.id}><span className="grid min-w-0 gap-[3px]"><strong className="break-all text-[12px] text-[#000000]">{item.filename}</strong><small className="text-[11px] font-semibold text-[#315b43]">{evidenceSourceLabel(item.source)}</small><small className="text-[11px] text-[#000000]">Uploaded by {item.uploadedBy||"uploader unavailable"} · {formatDateTime(item.uploadedAt)}</small><small className="text-[11px] text-[#000000]">{item.fieldKey} · {(item.sizeBytes/1024/1024).toFixed(1)} MB</small></span><button type="button" className="button-secondary" disabled={item.scanStatus==="REJECTED"} onClick={()=>void viewCaseEvidence(item)}>{item.scanStatus==="REJECTED"?"Unavailable":"View evidence"}</button></li>)}</ul>}</section>}
    {!initialFormId && !requestPending && !requestFailed && availableForms.length > 1 && (shouldUseSearchableSelect(availableForms.length)
      ? <SearchableSelect id="operational-form-selector" label="Published form" options={availableForms.map(form=>({value:form.id,label:`${form.name}${defectId?"":` · v${form.versionNumber}`}`}))} value={formId} placeholder="Choose a form…" onChange={value=>{setFormId(value);setValues({});setFiles({});setPendingFiles({});setUploadErrors({});setOptions({});setMessage("");initialUploadsStarted.current=false;}} className="my-[10px]"/>
      : <label className="flex flex-col gap-[7px] my-[10px]">Published form<select value={formId} onChange={event => { setFormId(event.target.value); setValues({}); setFiles({}); setPendingFiles({}); setUploadErrors({}); setOptions({}); setMessage(""); initialUploadsStarted.current=false; }}><option value="">Choose a form…</option>{availableForms.map(form => <option key={`${form.id}:${form.versionId}`} value={form.id}>{form.name}{defectId ? "" : ` · v${form.versionNumber}`}</option>)}</select></label>)}
    {selected && <>
      {definitionIssue ? <p role="alert">{definitionIssue}</p> : <>
        {reportBindingIssue && !previewOnly && <p className="configuration-field-error" role="alert">This form is unavailable for new defect reports right now. Please contact your organization administrator.</p>}
        {previewOnly || preparationMode ? <div className="operational-form-fields grid grid-cols-[repeat(2,minmax(0,1fr))] gap-x-4 gap-y-[10px] mt-4 max-[700px]:grid-cols-[minmax(0,1fr)]" aria-readonly={previewOnly || undefined}>{selectedFields.map(field => renderField(field, previewOnly))}{preparationMode&&message&&<p className="configuration-field-error" role="alert">{message}</p>}</div> : <>
        {defectId && <form onSubmit={submit} className="operational-form-fields grid grid-cols-[repeat(2,minmax(0,1fr))] gap-x-4 gap-y-[10px] mt-4 max-[700px]:grid-cols-[minmax(0,1fr)]">{selectedFields.map(field => renderField(field, false))}{message && <p className="configuration-field-error" role="alert">{message}</p>}{successMessage&&<p className="col-span-full m-0 rounded-lg border border-[#cfe6d4] bg-[#f1faf3] p-3 text-sm font-medium text-[#2f7042]" role="status">{successMessage}</p>}<button className="button-primary" disabled={submitting || Boolean(uploading) || Object.values(files).flat().some(file => file.scanStatus === "REJECTED")}>{submitting ? "Submitting…" : `Submit ${selected.purpose.replaceAll("_", " ").toLowerCase()} form`}</button></form>}
      </>}
      </>}
    </>}
    {evidencePreview&&<AttachmentPreviewDialog file={evidencePreview.file} url={evidencePreview.url} loading={!evidencePreview.url&&!evidencePreview.error} error={evidencePreview.error} onDownload={downloadCaseEvidence} onClose={()=>setEvidencePreview(null)}/>}
  </section>;

  function renderField(field: Field, readOnly: boolean) {
    const areaKey = field.optionFilter?.areaFieldKey;
    const componentKey = field.optionFilter?.componentFieldKey;
    const currentArea = areaKey ? String(values[areaKey] || "") : areaId;
    const currentComponent = componentKey ? String(values[componentKey] || "") : "";
    const choices = options[`${selected?.versionId}:${field.key}:${currentArea}:${currentComponent}`] || [];
    const id = `operational-${field.key}`;
    const uploaded = files[field.key] || [];
    const disabled = readOnly || Boolean(field.readOnly) || (Boolean(areaKey && !currentArea || componentKey && !currentComponent));
    const isSlaDate=field.binding==="SLA_TARGET_DATE";
    const datePolicy=slaPolicyQuery.data;
    const evidenceRequired=purpose==="CORRECTIVE_ACTION"&&field.binding==="EVIDENCE_ATTACHMENTS"&&field.requiredWhen?.equals==="CLOSE_SUBMITTED"&&selectedMaintenanceStatus==="CLOSE_SUBMITTED";
    const fieldRequired=isSlaDate&&purpose==="CORRECTIVE_ACTION"?Boolean(datePolicy?.policy.dateRequired):evidenceRequired||isFieldRequired(field,values);
    if (field.type === "SECTION") return <div className="operational-form-section" key={field.key}><h4>{field.label}</h4>{field.helpText && <p>{field.helpText}</p>}</div>;
    if(field.type==="AIRCRAFT_SELECT"){
      return <div className="dynamic-aircraft-select grid min-w-0 content-start gap-[7px] self-start" key={field.key}>
        {preparationMode&&!readOnly
          ? <AircraftRegistrationSelect request={request} cacheKey={`${aircraftOptionsCacheKey}:${selected?.id}:${selected?.versionId}`} areaId={areaId} formId="defect-report-form" id={id} label={field.label} helpText={field.helpText} required={field.required} selected={selectedAircraft} onSelect={option=>{setSelectedAircraft(option);onAircraftSelected?.(option);}} />
          : <><span className="dynamic-form-field-label">{field.label}</span><p className="selected-aircraft-context" role="status">Aircraft identity is controlled by the defect record and cannot be changed here.</p></>}
      </div>;
    }
    if(field.type.includes("UPLOAD")){
      const pending=pendingFiles[field.key]||EMPTY_FILES;
      const fileRules=field.fileRules;
      const accept=[...(fileRules?.mimeTypes||[]),...(fileRules?.extensions||[]).map(extension=>`.${extension.replace(/^\./,"")}`)].join(",");
      const minCount=fieldRequired?Math.max(1,fileRules?.minCount||0):fileRules?.minCount||0;
      return <div key={field.key} className="operational-upload-control">
        <label htmlFor={`evidence-${field.key}`}><span className="dynamic-form-field-label">{field.label}{fieldRequired&&<span className="dynamic-form-required" aria-hidden="true">*</span>}</span></label>
        {field.helpText&&<small>{field.helpText}</small>}
        <EvidenceUploadField fieldKey={field.key} label={field.label} type={field.type} required={fieldRequired} disabled={disabled||(!defectId&&!preparationMode)}
          accept={accept} multiple={(fileRules?.maxCount||1)>1} maxCount={fileRules?.maxCount||1} minCount={minCount} pendingFiles={pending} errors={uploadErrors[field.key]||{}} uploaded={uploaded}
          maxFileSizeBytes={fileRules?.maxFileSizeBytes||20*1024*1024} maxTotalSizeBytes={maxTotalBytesForField(field)}
          uploading={uploading===field.key} form={preparationMode?"defect-report-form":undefined} onSelect={chosen=>void uploadMany(field,chosen)}
          onRemovePending={file=>removePendingFile(field,file)} onRetry={file=>void upload(field,file)} onRemoveUploaded={file=>void removeFile(field,file)}
          onDismissError={errorKey=>setUploadErrors(old=>{const next={...old,[field.key]:{...(old[field.key]||{})}};delete next[field.key][errorKey];return next;})} />
      </div>;
    }
    if(field.binding==="CLOSURE_DECISION"){
      const selectedDecision=String(values[field.key]||"");
      return <fieldset className="closure-decision-control my-1 grid min-w-0 grid-cols-1 gap-[10px] border-0 p-0 col-span-full" key={field.key} disabled={disabled}>
        <legend className="dynamic-form-field-label">{field.label}{field.required&&<span className="dynamic-form-required" aria-hidden="true">*</span>}</legend>
        {field.helpText&&<small>{field.helpText}</small>}
        <div role="group" aria-label={field.label} className="closure-decision-buttons flex flex-wrap gap-[10px]">{choices.map(choice=><button key={choice.id} type="button" className={selectedDecision===choice.code?"button-primary":"button-secondary"} aria-pressed={selectedDecision===choice.code} onClick={()=>changeValue(field,choice.code)}>{choice.label}</button>)}</div>
        {!choices.length&&<p role="status">Loading closure decision options…</p>}
      </fieldset>;
    }
    if ((field.type === "DROPDOWN" || field.type === "MULTI_SELECT") && shouldUseSearchableSelect(choices.length, 500)) {
      const searchableOptions = choices.map(option => ({ value: option.id, label: option.label }));
      if (field.type === "MULTI_SELECT") {
        return <SearchableMultiSelect key={field.key} id={id} label={field.label} helpText={field.helpText} options={searchableOptions}
          form={preparationMode ? "defect-report-form" : undefined} required={fieldRequired && !readOnly} disabled={disabled}
          value={Array.isArray(values[field.key]) ? values[field.key] as string[] : []}
          onChange={next => changeValue(field, next)} />;
      }
      return <SearchableSelect key={field.key} id={id} label={field.label} helpText={field.helpText} options={searchableOptions}
        form={preparationMode ? "defect-report-form" : undefined} required={fieldRequired && !readOnly} disabled={disabled}
        value={String(values[field.key] || "")} onChange={next => changeValue(field, next)} />;
    }
    return <label className="flex flex-col gap-[7px] my-[10px]" key={field.key} htmlFor={id}><span className="dynamic-form-field-label">{field.label}{fieldRequired && <span className="dynamic-form-required" aria-hidden="true">*</span>}</span>{field.helpText && <small>{field.helpText}</small>}
      {isSlaDate&&purpose==="CORRECTIVE_ACTION"&&slaPolicyQuery.isError&&<button type="button" className="button-secondary" onClick={()=>void slaPolicyQuery.refetch()}>Retry policy</button>}
      {field.type === "TEXTAREA" ? <textarea id={id} form={preparationMode ? "defect-report-form" : undefined} required={field.required && !readOnly} disabled={disabled} value={String(values[field.key] || "")} minLength={field.textRules?.minLength ?? undefined} maxLength={field.textRules?.maxLength ?? undefined} onChange={event => changeValue(field, event.target.value)} />
        : field.type === "DROPDOWN" || field.type === "MULTI_SELECT" ? <select id={id} form={preparationMode ? "defect-report-form" : undefined} required={field.required && !readOnly} disabled={disabled} multiple={field.type === "MULTI_SELECT"} value={field.type === "MULTI_SELECT" ? (Array.isArray(values[field.key]) ? values[field.key] as string[] : []) : String(values[field.key] || "")} onChange={event => changeValue(field, field.type === "MULTI_SELECT" ? Array.from(event.target.selectedOptions).map(option => option.value) : event.target.value)}><option value="">Choose…</option>{choices.map(option => <option key={option.id} value={option.id}>{option.label}</option>)}</select>
          : field.type === "CHECKBOX" ? <input id={id} form={preparationMode ? "defect-report-form" : undefined} type="checkbox" disabled={disabled} checked={Boolean(values[field.key])} onChange={event => changeValue(field, event.target.checked)} />
            : <input id={id} form={preparationMode ? "defect-report-form" : undefined} type={field.type === "NUMBER" ? "number" : field.type === "DATE" ? "date" : "text"} required={fieldRequired && !readOnly} disabled={disabled||(isSlaDate&&purpose==="CORRECTIVE_ACTION"&&(!selectedMaintenanceStatus||slaPolicyQuery.isPending||slaPolicyQuery.isError))} value={String(values[field.key] ?? "")} min={isSlaDate&&purpose==="CORRECTIVE_ACTION"?datePolicy?.minDate:field.type === "NUMBER" ? field.numericRules?.min ?? undefined : field.type === "DATE" ? field.dateRules?.minDate ?? undefined : undefined} max={isSlaDate&&purpose==="CORRECTIVE_ACTION"?datePolicy?.maxDate:field.type === "NUMBER" ? field.numericRules?.max ?? undefined : field.type === "DATE" ? field.dateRules?.maxDate ?? undefined : undefined} step={field.type === "NUMBER" ? 1 / 10 ** (field.numericRules?.precision ?? 0) : undefined} minLength={field.type === "TEXT" ? field.textRules?.minLength ?? undefined : undefined} maxLength={field.type === "TEXT" ? field.textRules?.maxLength ?? undefined : field.type==="TEXTAREA"?field.textRules?.maxLength??undefined:undefined} pattern={field.type === "TEXT" ? field.textRules?.pattern || undefined : undefined} onChange={event => changeValue(field, event.target.value)} />}
    </label>;
  }
}
