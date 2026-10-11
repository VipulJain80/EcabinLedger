"use client";

import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import Image from "next/image";
import { useQuery } from "@tanstack/react-query";
import { alert, responseErrorMessage } from "@/common/alerts";
import { isPublishedFormForArea, orderedPublishedFields, unsupportedPublishedField } from "./publishedFormDefinition";
import { defectReportBindingIssue } from "./defectReportMapping";

type Request = (path: string, init?: RequestInit) => Promise<Response>;
type Field = {
  key: string;
  label: string;
  type: string;
  helpText?: string | null;
  required: boolean;
  order: number;
  visible?: boolean | null;
  readOnly?: boolean | null;
  optionSource?: string | null;
  optionFilter?: { areaFieldKey?: string | null; componentFieldKey?: string | null } | null;
  fileRules?: { extensions: string[]; mimeTypes: string[]; maxFileSizeBytes: number; minCount: number; maxCount: number } | null;
  textRules?: { minLength?: number | null; maxLength?: number | null; alphaOnly?: boolean; alphanumeric?: boolean; pattern?: string | null } | null;
  numericRules?: { min?: number | null; max?: number | null; precision?: number | null } | null;
  dateRules?: { minDate?: string | null; maxDate?: string | null } | null;
  binding?: import("./defectReportMapping").DefectBinding | null;
};
type Purpose = "DEFECT_REPORT" | "INSPECTION" | "CORRECTIVE_ACTION" | "VERIFICATION" | "CLOSURE_APPROVAL" | "REOPEN";
type Definition = { id: string; name: string; areaId: string; purpose: Purpose; workflowStage: string; versionId: string; versionNumber: number; configuration: { fields: Field[] } };
type Choice = { id: string; label: string; code: string };
type Uploaded = { id: string; filename: string; scanStatus: string };
type Submission = { id: string; versionNumber: number; submittedBy: string; submittedAt: string; snapshot: { defectCategory: string; componentId: string; conditionId: string; zone: string; rowNumber: number | null; seatReference: string | null; title: string; description: string; severityId: string } | null };

export default function OperationalFormRenderer({ request, defectId, areaId, areaName, areaCode, initialFormId, initialVersionId, purpose = "DEFECT_REPORT", workflowStage = "INITIAL_REPORT", previewOnly = false, preparationMode = false, initialAnswers, initialFiles, onAnswersChange, onFilesChange, onPreparationReady }: {
  request: Request;
  defectId?: string;
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
}) {
  const [formId, setFormId] = useState(initialFormId || "");
  const [values, setValues] = useState<Record<string, unknown>>(initialAnswers || {});
  const [files, setFiles] = useState<Record<string, Uploaded[]>>({});
  const [pendingFiles, setPendingFiles] = useState<Record<string, File[]>>(initialFiles || {});
  const [previews, setPreviews] = useState<Record<string, string[]>>({});
  const previewUrls = useRef<string[]>([]);
  const [uploading, setUploading] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState("");
  const [options, setOptions] = useState<Record<string, Choice[]>>({});
  const submissionGuard = useRef(false);
  const initialUploadsStarted = useRef(false);

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
      const response = await request(`/forms/${encodeURIComponent(initialFormId! as string)}/versions/${encodeURIComponent(initialVersionId! as string)}/definition`);
      if (!response.ok) throw new Error(await responseErrorMessage(response, "The selected published form version is no longer available."));
      return response.json();
    },
  });

  const availableForms = available.data || [];
  const selectedFormId = initialFormId || formId || (availableForms.length === 1 ? availableForms[0].id : "");
  const selected = initialFormId ? initialDefinition.data : availableForms.find(form => form.id === selectedFormId);
  const selectedFields = useMemo(() => orderedPublishedFields(selected?.configuration.fields || []).filter(field => field.visible !== false), [selected]);
  const unsupportedField = unsupportedPublishedField(selectedFields);
  const reportBindingIssue = preparationMode && selected ? defectReportBindingIssue(selectedFields, areaCode) : "";
  const definitionIssue = selected && !isPublishedFormForArea(selected, areaId)
    ? "The published form does not belong to the selected cabin area. Contact your administrator."
    : unsupportedField ? `Published form field “${unsupportedField.label}” uses unsupported type “${unsupportedField.type}”. Contact your administrator.` : "";
  useEffect(() => {
    if (preparationMode) onPreparationReady?.(Boolean(initialDefinition.isSuccess && selected && !definitionIssue && !reportBindingIssue));
  }, [definitionIssue, initialDefinition.isSuccess, onPreparationReady, preparationMode, reportBindingIssue, selected]);
  const submissions = useQuery<Submission[]>({
    queryKey: ["defect-form-submissions", defectId],
    enabled: Boolean(selected && defectId && !previewOnly),
    queryFn: async () => {
      const response = await request(`/defects/${encodeURIComponent(defectId! as string)}/form-submissions`);
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load submitted forms."));
      return response.json();
    },
  });

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

  useEffect(() => { const createdUrls=previewUrls.current; return()=>createdUrls.forEach(url=>URL.revokeObjectURL(url)); }, []);

  function changeValue(field: Field, value: unknown) {
    const next = { ...values, [field.key]: value };
    for (const dependent of selectedFields) {
      if (dependent.optionFilter?.areaFieldKey === field.key || dependent.optionFilter?.componentFieldKey === field.key) delete next[dependent.key];
    }
    setValues(next);
    if (preparationMode) onAnswersChange?.(next);
  }

  const upload = useCallback(async (field: Field, file: File) => {
    if (!selected || !defectId) return;
    setUploading(field.key); setMessage("");
    try {
      const body = new FormData(); body.append("file", file);
      const response = await request(`/defects/${defectId}/forms/${selected.id}/versions/${selected.versionId}/fields/${encodeURIComponent(field.key)}/attachments`, { method: "POST", body });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Evidence upload failed."));
      const uploaded = await response.json() as Uploaded;
      setFiles(old => ({ ...old, [field.key]: [...(old[field.key] || []), uploaded] }));
      setPendingFiles(old=>({...old,[field.key]:(old[field.key]||[]).filter(item=>item!==file)}));
      if (uploaded.scanStatus !== "CLEAN") setMessage(`${uploaded.filename} is ${uploaded.scanStatus.toLowerCase()}; submission is blocked until evidence passes the configured scan.`);
    } catch (error) { setMessage(error instanceof Error ? error.message : "Evidence upload failed."); }
    finally { setUploading(null); }
  }, [defectId, request, selected]);

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
    const remaining = Math.max(0, (rules?.maxCount || 1) - (files[field.key]?.length || 0));
    if (incoming.length > remaining) { setMessage(`Select no more than ${remaining} additional file(s) for ${field.label}.`); return; }
    if (!defectId && preparationMode) {
      const existing=pendingFiles[field.key]||[];
      if(incoming.length+existing.length>(rules?.maxCount||1)){setMessage(`Select no more than ${rules?.maxCount||1} image(s) for ${field.label}.`);return;}
      for(const file of incoming){const ext=file.name.split(".").pop()?.toLowerCase()||"";if(file.size>(rules?.maxFileSizeBytes||0)){setMessage(`${file.name} exceeds the configured file-size limit.`);return;}if(!rules?.extensions.some(value=>value.toLowerCase()===ext)||!rules.mimeTypes.includes(file.type)){setMessage(`${file.name} is not an allowed image type for ${field.label}.`);return;}}
      const urls=incoming.map(file=>{const url=URL.createObjectURL(file);previewUrls.current.push(url);return url;});const next={...pendingFiles,[field.key]:[...existing,...incoming]};setPendingFiles(next);setPreviews(current=>({...current,[field.key]:[...(current[field.key]||[]),...urls]}));onFilesChange?.(next);setMessage("");return;
    }
    for (const file of incoming) {
      if (file.size > (rules?.maxFileSizeBytes || 0)) { setMessage(`${file.name} exceeds the configured file-size limit.`); return; }
      await upload(field, file);
    }
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
    submissionGuard.current = true;
    const answers = { ...values };
    for (const field of selectedFields.filter(item => item.type.includes("UPLOAD"))) answers[field.key] = (files[field.key] || []).map(file => file.id);
    setSubmitting(true); setMessage("");
    try {
      const response = await request(`/defects/${defectId}/form-submissions`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ formId: selected.id, versionId: selected.versionId, purpose: selected.purpose, answers }) });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Form submission failed validation."));
      await alert.success("Operational form submitted and added to the defect audit trail.");
      setValues({}); setFiles({}); await submissions.refetch();
    } catch (error) { setMessage(error instanceof Error ? error.message : "Form submission failed."); }
    finally { submissionGuard.current = false; setSubmitting(false); }
  }

  const requestPending = initialFormId ? initialDefinition.isPending : available.isPending;
  const requestError = initialFormId ? initialDefinition.error : available.error;
  const requestFailed = initialFormId ? initialDefinition.isError : available.isError;
  const noForms = !initialFormId && available.isSuccess && availableForms.length === 0;

  return <section className={`operational-form-panel${previewOnly || preparationMode ? " operational-form-report-context" : ""}`} aria-label={previewOnly ? "Selected published operational form preview" : preparationMode ? "Published defect report form" : "Area inspection form"}>
    {selected && <header className="operational-form-heading"><div>{!preparationMode && <span className="eyebrow">PUBLISHED · VERSION {selected.versionNumber}</span>}<h3>{selected.name}</h3><p>Area: <b>{selected.areaId === areaId ? areaName || "Selected cabin area" : "Area configuration mismatch"}</b></p></div></header>}
    {requestPending && <p role="status">Loading the selected published form…</p>}
    {requestFailed && <p role="alert">{requestError instanceof Error ? requestError.message : "Could not load the selected published form."}</p>}
    {noForms && <p role="status">{purpose === "DEFECT_REPORT" ? "No published operational form is configured for this cabin area. Please contact your administrator." : `No published ${purpose.replaceAll("_", " ").toLowerCase()} form is configured for this workflow stage. Please contact your administrator.`}</p>}
    {!initialFormId && !requestPending && !requestFailed && availableForms.length > 1 && <label>Published form<select value={formId} onChange={event => { setFormId(event.target.value); setValues({}); setFiles({}); setOptions({}); setMessage(""); }}><option value="">Choose a form…</option>{availableForms.map(form => <option key={`${form.id}:${form.versionId}`} value={form.id}>{form.name} · v{form.versionNumber}</option>)}</select></label>}
    {selected && <>
      {definitionIssue ? <p role="alert">{definitionIssue}</p> : <>
        {reportBindingIssue && <p className="configuration-field-error" role="alert">{reportBindingIssue}</p>}
        {previewOnly || preparationMode ? <div className="operational-form-fields" aria-readonly={previewOnly || undefined}>{selectedFields.map(field => renderField(field, previewOnly))}</div> : <>
        {defectId && <form onSubmit={submit} className="operational-form-fields">{selectedFields.map(field => renderField(field, false))}{message && <p className="configuration-field-error" role="alert">{message}</p>}<button className="button-primary" disabled={submitting || Boolean(uploading) || Object.values(files).flat().some(file => file.scanStatus !== "CLEAN")}>{submitting ? "Submitting…" : `Submit ${selected.purpose.replaceAll("_", " ").toLowerCase()} form`}</button></form>}
        {defectId && <div className="operational-form-history"><h4>Submitted forms</h4>{submissions.isPending ? <p>Loading submission history…</p> : submissions.isError ? <p role="alert">{submissions.error.message}</p> : !submissions.data?.length ? <p>No forms have been submitted for this defect.</p> : submissions.data.map(item => <details key={item.id}><summary>Version {item.versionNumber} · {new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(new Date(item.submittedAt))} · {item.submittedBy}</summary>{item.snapshot?<dl className="form-submission-snapshot"><dt>Category</dt><dd>{item.snapshot.defectCategory}</dd><dt>Component</dt><dd>{item.snapshot.componentId}</dd><dt>Condition</dt><dd>{item.snapshot.conditionId}</dd><dt>Location</dt><dd>{item.snapshot.zone}</dd><dt>Row / Seat</dt><dd>{[item.snapshot.rowNumber,item.snapshot.seatReference].filter(Boolean).join(" / ")||"—"}</dd><dt>Title</dt><dd>{item.snapshot.title}</dd><dt>Description</dt><dd>{item.snapshot.description}</dd><dt>Severity reference</dt><dd>{item.snapshot.severityId}</dd></dl>:<p>Evidence or workflow submission recorded against the published version.</p>}</details>)}</div>}
      </>}
      </>}
    </>}
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
    if (field.type === "SECTION") return <div className="operational-form-section" key={field.key}><h4>{field.label}</h4>{field.helpText && <p>{field.helpText}</p>}</div>;
    return <label key={field.key} htmlFor={id}><span className="dynamic-form-field-label">{field.label}{field.required && <span className="dynamic-form-required" aria-hidden="true">*</span>}</span>{field.helpText && <small>{field.helpText}</small>}
      {field.type === "TEXTAREA" ? <textarea id={id} form={preparationMode ? "defect-report-form" : undefined} required={field.required && !readOnly} disabled={disabled} value={String(values[field.key] || "")} minLength={field.textRules?.minLength ?? undefined} maxLength={field.textRules?.maxLength ?? undefined} onChange={event => changeValue(field, event.target.value)} />
        : field.type === "DROPDOWN" || field.type === "MULTI_SELECT" ? <select id={id} form={preparationMode ? "defect-report-form" : undefined} required={field.required && !readOnly} disabled={disabled} multiple={field.type === "MULTI_SELECT"} value={field.type === "MULTI_SELECT" ? (Array.isArray(values[field.key]) ? values[field.key] as string[] : []) : String(values[field.key] || "")} onChange={event => changeValue(field, field.type === "MULTI_SELECT" ? Array.from(event.target.selectedOptions).map(option => option.value) : event.target.value)}><option value="">Choose…</option>{choices.map(option => <option key={option.id} value={option.id}>{option.label}</option>)}</select>
          : field.type === "CHECKBOX" ? <input id={id} form={preparationMode ? "defect-report-form" : undefined} type="checkbox" disabled={disabled} checked={Boolean(values[field.key])} onChange={event => changeValue(field, event.target.checked)} />
            : field.type.includes("UPLOAD") ? <><input id={id} form={preparationMode ? "defect-report-form" : undefined} type="file" required={preparationMode&&field.required&&(pendingFiles[field.key]?.length||0)<(field.fileRules?.minCount||0)} disabled={disabled || (!defectId&&!preparationMode)} accept={(field.fileRules?.mimeTypes || []).join(",")} multiple={(field.fileRules?.maxCount || 1) > 1} onChange={event => { void uploadMany(field, Array.from(event.target.files || [])); event.target.value = ""; }} /><small>Maximum {Math.round((field.fileRules?.maxFileSizeBytes || 0) / 1048576)} MB per file · {field.fileRules?.minCount}–{field.fileRules?.maxCount} files</small>{preparationMode&&previews[field.key]?.map((url,index)=><Image unoptimized width={360} height={220} className="operational-evidence-preview" key={url} src={url} alt={`${field.label} preview ${index+1}`} />)}{!defectId && !preparationMode && !readOnly && <small>Save the defect before uploading evidence.</small>}{uploading === field.key && <small role="status">Uploading and scanning…</small>}{uploaded.map(file => <small className="operational-uploaded-file" key={file.id}>{file.filename} · {file.scanStatus}<button type="button" className="button-secondary" disabled={uploading === field.key} onClick={() => void removeFile(field, file)}>Remove</button></small>)}</>
              : <input id={id} form={preparationMode ? "defect-report-form" : undefined} type={field.type === "NUMBER" ? "number" : field.type === "DATE" ? "date" : "text"} required={field.required && !readOnly} disabled={disabled} value={String(values[field.key] ?? "")} min={field.type === "NUMBER" ? field.numericRules?.min ?? undefined : field.type === "DATE" ? field.dateRules?.minDate ?? undefined : undefined} max={field.type === "NUMBER" ? field.numericRules?.max ?? undefined : field.type === "DATE" ? field.dateRules?.maxDate ?? undefined : undefined} step={field.type === "NUMBER" ? 1 / 10 ** (field.numericRules?.precision ?? 0) : undefined} minLength={field.type === "TEXT" ? field.textRules?.minLength ?? undefined : undefined} maxLength={field.type === "TEXT" ? field.textRules?.maxLength ?? undefined : undefined} pattern={field.type === "TEXT" ? field.textRules?.pattern || undefined : undefined} onChange={event => changeValue(field, event.target.value)} />}
    </label>;
  }
}
