"use client";

import { FormEvent, KeyboardEvent as ReactKeyboardEvent, useCallback, useEffect, useRef, useState } from "react";
import ConfigurationPage from "../configuration";
import FleetStatusPage from "@/common/fleet/FleetStatusPage";
import DynamicReportViewer from "@/common/reports/DynamicReportViewer";
import AircraftDefectListPage from "@/common/fleet/AircraftDefectListPage";
import ReportDefectPage from "@/common/fleet/ReportDefectPage";
import { FleetAircraft } from "@/common/fleet/FleetStatusPage";
import MaintenanceWorkQueuePage from "@/common/fleet/MaintenanceWorkQueuePage";
import ReviewerWorkQueuePage from "@/common/fleet/ReviewerWorkQueuePage";
import GalleyReviewerDecisionForm from "@/common/fleet/GalleyReviewerDecisionForm";
import DefectDetailsPage from "@/common/fleet/DefectDetailsPage";
import MaintenanceTeamReassignmentDialog, { ReassignTeamButton } from "@/common/fleet/MaintenanceTeamReassignmentDialog";
import { canReassignMaintenance } from "@/common/fleet/defectDetailsModel";
import AccessStatePage, { accessFailureMessage } from "@/common/auth/AccessStatePage";
import DialogPortal from "@/common/dialogs/DialogPortal";
import OperationalFormRenderer from "@/common/forms/OperationalFormRenderer";
import { formPurposeForStatus } from "@/common/forms/formResolution";
import { defectReportPayload, ReportField } from "@/common/forms/defectReportMapping";
import NotificationBell from "@/common/notifications/NotificationBell";
import ApiRateLimitsPage from "@/common/configuration/ApiRateLimitsPage";
import UserLoginMonitoringPage from "@/common/configuration/UserLoginMonitoringPage";
import { alert, responseErrorMessage } from "@/common/alerts";
import { hasPermission } from "@/common/rbac/permissions";
import { applicationBasePath, clearLocalSession, getApplicationAccessToken, getLocalSession } from "@/common/auth/localSession";
import { formatDateTime } from "@/common/dateTime";
import { useDefectWorkflowStatuses, workflowStatusLabel, WorkflowStatusBadge, SeverityBadge } from "@/common/workflow/workflowStatuses";

type Defect = {
  id: string; reference: string; tailNumber: string; aircraftType: string; location: string; category: string; zone: string;
  areaId: string;
  title: string; description: string; component: string; severity: string; severityName?: string; status: string; reportedBy: string; assignedTo: string | null; assignedTeam?: string | null; routingAttention?: string | null;
  dueAt: string | null; activeActionId: string | null; conditionName?: string | null; reportedAt: string; updatedAt: string; closedAt?: string | null;
};
type Page = { items: Defect[]; nextCursor: string | null };
type Summary = { openDefects: number; criticalDefects: number; reportedToday: number; closedThisMonth: number };
type Identity = { organizationName: string; applicationName: string; displayName: string; role: string; permissions: string[] };
type NavigationItem = { key: string; label: string; icon: string; section: string; children: NavigationItem[] };
type SearchFilters = { query: string; fromDate: string; toDate: string };
function defaultRegisterDateRange(): Pick<SearchFilters, "fromDate" | "toDate"> {
  const parts = new Intl.DateTimeFormat("en-GB", { timeZone: "Asia/Kolkata", year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(new Date());
  const value = (type: Intl.DateTimeFormatPartTypes) => parts.find(part => part.type === type)?.value ?? "";
  const today = `${value("year")}-${value("month")}-${value("day")}`;
  const start = new Date(`${today}T12:00:00Z`);
  start.setUTCDate(start.getUTCDate() - 7);
  return { fromDate: start.toISOString().slice(0, 10), toDate: today };
}
function registerDateRangeLabel(filters: SearchFilters): string {
  const defaults = defaultRegisterDateRange();
  if (filters.fromDate === defaults.fromDate && filters.toDate === defaults.toDate) return "Last 7 days";
  return filters.fromDate || filters.toDate ? "Custom dates" : "All dates";
}
type MasterOption = { id?: string; code: string; name: string; areaId?: string };
type AuditEvent = { id: string; eventType: string; fromStatus: string | null; toStatus: string | null; note: string; evidenceReference: string | null; inspectionOutcome: string | null; actorId: string; occurredAt: string };
type SelectedReportForm = { id: string; versionId: string; versionNumber: number; name: string; configuration: { fields: ReportField[] } };
const API = process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080/api/v1";
const areas = ["ALL AREAS", "CABIN", "GALLEY", "LAVATORY", "ATTENDANT_SEAT"];
const human = (value: string) => value.replaceAll("_", " ").toLowerCase().replace(/\b\w/g, (char) => char.toUpperCase());

async function apiFetch(path: string, init: RequestInit = {}) {
  const token = await getApplicationAccessToken();
  const headers = new Headers(init.headers);
  if (token) headers.set("Authorization", `Bearer ${token}`);
  return fetch(path, { ...init, headers, credentials: "omit", cache: "no-store" });
}

async function configurationFetch(path: string, init: RequestInit = {}) {
  return apiFetch(`${API}${path}`, init);
}

async function operationalFormFetch(path: string, init: RequestInit = {}) {
  return apiFetch(`${API}${path}`, init);
}

async function fleetStatusFetch(path: string, init: RequestInit = {}) {
  return apiFetch(`${API}${path}`, init);
}

export default function Home() {
  const workflowStatusesQuery = useDefectWorkflowStatuses(fleetStatusFetch);
  const workflowStatuses = workflowStatusesQuery.data?.items || [];
  const [items, setItems] = useState<Defect[]>([]);
  const [filter, setFilter] = useState("ALL STATUS");
  const [area, setArea] = useState("ALL AREAS");
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [dialog, setDialog] = useState(false);
  const [saving, setSaving] = useState(false);
  const reportIdempotencyKey = useRef<string | null>(null);
  const [selected, setSelected] = useState<Defect | null>(null);
  const [reassignmentTarget, setReassignmentTarget] = useState<Defect | null>(null);
  const [openDefectId, setOpenDefectId] = useState<string | null>(null);
  const [openDefectAircraftListId, setOpenDefectAircraftListId] = useState<string | null>(null);
  const defectReturnScrollY = useRef(0);
  const [selectedAircraftId, setSelectedAircraftId] = useState("");
  const [selectedAircraft, setSelectedAircraft] = useState<FleetAircraft | null>(null);
  const reportModalReturnContext = useRef<{ aircraftId: string; aircraft: FleetAircraft | null } | null>(null);
  const [selectedFormForReport, setSelectedFormForReport] = useState<SelectedReportForm | null>(null);
  const [reportFormAnswers, setReportFormAnswers] = useState<Record<string, unknown>>({});
  const [reportFormFiles, setReportFormFiles] = useState<Record<string, File[]>>({});
  const updateReportFormAnswers = useCallback((answers: Record<string, unknown>) => { reportIdempotencyKey.current = null; setReportFormAnswers(answers); }, []);
  const updateReportFormFiles = useCallback((files: Record<string, File[]>) => { reportIdempotencyKey.current = null; setReportFormFiles(files); }, []);
  const [reportFormReady, setReportFormReady] = useState(false);
  const updateReportFormReady = useCallback((ready: boolean) => setReportFormReady(ready), []);
  const [initialFormForDefect, setInitialFormForDefect] = useState<{ defectId: string; formId: string; versionId: string; answers: Record<string, unknown>; files?: Record<string, File[]> } | null>(null);
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [summary, setSummary] = useState<Summary>({ openDefects: 0, criticalDefects: 0, reportedToday: 0, closedThisMonth: 0 });
  const [summaryAvailable, setSummaryAvailable] = useState(false);
  const [identity, setIdentity] = useState<Identity | null>(null);
  const [applicationName, setApplicationName] = useState("");
  const identityAvailable = useRef(false);
  const [authorizationError, setAuthorizationError] = useState("");
  const routeAccessChecked = useRef(false);
  const [navigation, setNavigation] = useState<NavigationItem[]>([]);
  const [openNavigationMenu, setOpenNavigationMenu] = useState<string | null>(null);
  const navigationRef = useRef<HTMLElement | null>(null);
  const profileRef = useRef<HTMLDivElement | null>(null);
  const [profileOpen, setProfileOpen] = useState(false);
  const [notificationOpen, setNotificationOpen] = useState(false);
  const [configurationKey, setConfigurationKey] = useState("");
  const [currentNavKey, setCurrentNavKey] = useState("home");
  const [searchDraft, setSearchDraft] = useState<SearchFilters>(() => ({ query: "", ...defaultRegisterDateRange() }));
  const [searchFilters, setSearchFilters] = useState<SearchFilters>(() => ({ query: "", ...defaultRegisterDateRange() }));
  const [searchExecuted, setSearchExecuted] = useState(true);
  const [areaMasterOptions, setAreaMasterOptions] = useState<MasterOption[]>([]);
  const [selectedAreaId, setSelectedAreaId] = useState("");
  const [localSessionEmail, setLocalSessionEmail] = useState<string | null>(() => getLocalSession()?.email || null);
  const [activeSection, setActiveSection] = useState("defects");
  const currentPageLabel = navigation.flatMap((item) => [item, ...item.children]).find((item) => item.key === currentNavKey)?.label || (currentNavKey === "home" ? "Defect Summary" : "Defect LOG Book");

  useEffect(() => { const title = selectedAircraftId ? "Aircraft Defect List" : currentPageLabel; document.title = `${title} | ${identity?.applicationName?.trim() || "eCabin Ledger"}`; }, [currentPageLabel, selectedAircraftId, identity?.applicationName]);

  useEffect(() => {
    if (!openNavigationMenu) return;
    const closeOnOutsideClick = (event: PointerEvent) => {
      if (!navigationRef.current?.contains(event.target as Node)) setOpenNavigationMenu(null);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      const trigger = navigationRef.current?.querySelector<HTMLButtonElement>(`[data-navigation-trigger="${openNavigationMenu}"]`);
      setOpenNavigationMenu(null);
      trigger?.focus();
    };
    document.addEventListener("pointerdown", closeOnOutsideClick);
    document.addEventListener("keydown", closeOnEscape);
    return () => {
      document.removeEventListener("pointerdown", closeOnOutsideClick);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [openNavigationMenu]);

  useEffect(() => {
    if (!profileOpen) return;
    const closeOnOutsideClick = (event: PointerEvent) => {
      if (!profileRef.current?.contains(event.target as Node)) setProfileOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => { if (event.key === "Escape") setProfileOpen(false); };
    document.addEventListener("pointerdown", closeOnOutsideClick);
    document.addEventListener("keydown", closeOnEscape);
    return () => {
      document.removeEventListener("pointerdown", closeOnOutsideClick);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [profileOpen]);

  const load = useCallback(async (after: string | null = null, append = false) => {
    setLoading(true);
    const params = new URLSearchParams({ limit: "25" });
    if (filter !== "ALL STATUS") params.set("status", filter);
    if (area !== "ALL AREAS") params.set("location", area);
    if (searchFilters.query.trim()) params.set("q", searchFilters.query.trim());
    if (searchFilters.query.trim()) params.set("searchBy", "ALL");
    if (searchFilters.fromDate) params.set("fromDate", `${searchFilters.fromDate}T00:00:00+05:30`);
    if (searchFilters.toDate) params.set("toDate", `${searchFilters.toDate}T00:00:00+05:30`);
    if (append && after) params.set("cursor", after);
    try {
      const [identityResponse, navigationResponse] = await Promise.all([apiFetch(`${API}/me`), apiFetch(`${API}/navigation`)]);
      const rejectedResponse = [identityResponse, navigationResponse].find((candidate) => candidate.status === 401 || candidate.status === 403);
      if (rejectedResponse) {
        const expired = rejectedResponse.status === 401;
        setIdentity(null);
        identityAvailable.current = false;
        setNavigation([]);
        setItems([]);
        setSummary({ openDefects: 0, criticalDefects: 0, reportedToday: 0, closedThisMonth: 0 });
        setSummaryAvailable(false);
        setSelected(null);
        setEvents([]);
        routeAccessChecked.current = false;
        if (expired) {
          clearLocalSession();
          setLocalSessionEmail(null);
          window.location.replace(`${applicationBasePath}/login`);
          return;
        }
        throw new Error(expired
          ? "Your session has expired. Sign in through your organization’s enterprise access gateway."
          : "Your account is not authorized for this organization or application page. Contact your organization administrator.");
      }
      const failedRequest = [
        { label: "Account identity", response: identityResponse },
        { label: "Navigation", response: navigationResponse },
      ].find((item) => !item.response.ok);
      if (failedRequest) {
        if (failedRequest.response.status >= 500) throw new Error("Access unavailable");
        throw new Error(await responseErrorMessage(failedRequest.response, `Could not load ${failedRequest.label} (HTTP ${failedRequest.response.status}).`));
      }
      const [identityData, navigationData]: [Identity, NavigationItem[]] = await Promise.all([identityResponse.json(), navigationResponse.json()]);
      setIdentity(identityData);
      setApplicationName(identityData.applicationName || "");
      identityAvailable.current = true;
      setNavigation(navigationData);
      setAuthorizationError("");
      const hasDefectRead = identityData.permissions.includes("VIEW") || identityData.permissions.includes("DEFECT_VIEW_ALL");
      const hasDefectViewAll = identityData.permissions.includes("DEFECT_VIEW_ALL");
      const hasReportView = identityData.permissions.includes("REPORT_VIEW");
      const canOpenReports = hasReportView || identityData.permissions.includes("ADMINISTER_ORGANIZATION");
      if (!routeAccessChecked.current) {
        const accessibleItems = navigationData.flatMap((item) => [item, ...item.children]);
        const accessibleKeys = new Set(accessibleItems.map((item) => item.key));
        // Defect Summary is the post-login landing page. The register gives the
        // same defect-read capability, so keep users on the summary even when
        // a tenant's navigation configuration omitted its Home entry.
        const fallback = accessibleKeys.has("home") || accessibleKeys.has("defects")
          ? "home"
          : canOpenReports && accessibleKeys.has("reports") ? "reports"
          : accessibleItems.find((item) => item.children.length === 0)?.key;
        if (fallback) { setActiveSection("defects"); setCurrentNavKey(fallback); }
        else setAuthorizationError("Access denied");
        routeAccessChecked.current = true;
      }
      if (hasDefectRead) {
        const response = await apiFetch(`${API}/defects?${params}`);
        if (response.status === 401) throw new Error("Your session has expired. Sign in again.");
        if (!response.ok) throw new Error(await responseErrorMessage(response, `Could not load the defect register (HTTP ${response.status}).`));
        const data = await response.json() as Page;
        setItems((previous) => append ? [...previous, ...data.items] : data.items);
        setCursor(data.nextCursor);
      } else {
        setItems([]);
        setCursor(null);
      }
      if (hasDefectRead && (hasReportView || hasDefectViewAll)) {
        const summaryResponse = await apiFetch(`${API}/dashboard/summary`);
        if (summaryResponse.ok) {
          setSummary(await summaryResponse.json() as Summary);
          setSummaryAvailable(true);
        } else {
          setSummaryAvailable(false);
          setSummary({ openDefects: 0, criticalDefects: 0, reportedToday: 0, closedThisMonth: 0 });
        }
      } else {
        setSummaryAvailable(false);
        setSummary({ openDefects: 0, criticalDefects: 0, reportedToday: 0, closedThisMonth: 0 });
      }
      if (hasDefectRead || identityData.permissions.some(permission => ["REPORT", "REVIEW", "INSPECT", "PERFORM_ACTION", "VERIFY", "APPROVE", "CLOSE", "REOPEN"].includes(permission))) {
        const referenceResponse = await apiFetch(`${API}/reference-data`);
        if (referenceResponse.ok) {
        const referenceData: { areas: MasterOption[] } = await referenceResponse.json();
        setAreaMasterOptions(referenceData.areas);
        }
      }
    } catch (error) {
      const message = accessFailureMessage(error);
      setAuthorizationError(message);
      if (identityAvailable.current) await alert.error(message);
    }
    finally { setLoading(false); }
  }, [area, filter, searchFilters, setSelected]);

  useEffect(() => {
    let cancelled = false;
    void Promise.resolve().then(() => { if (!cancelled) return load(); });
    return () => { cancelled = true; };
  }, [load]);

  useEffect(() => {
    if (searchDraft.query === searchFilters.query) return;
    const timer = window.setTimeout(() => {
      setSearchFilters((current) => ({ ...current, query: searchDraft.query }));
      setCursor(null);
      setSearchExecuted(true);
    }, 350);
    return () => window.clearTimeout(timer);
  }, [searchDraft.query, searchFilters.query]);

  async function confirmLogout() {
    if (await alert.confirm({ title: "Sign out?", text: `You will leave the ${identity?.organizationName || "current"} workspace.`, confirmText: "Sign out" })) window.location.replace(`${applicationBasePath}/logout`);
  }

  function openNavigation(item: NavigationItem, parentKey?: string) {
    setOpenNavigationMenu(null);
    setOpenDefectId(null);
    reportModalReturnContext.current = null;
    if (item.key === "home") leaveAircraft();
    if(item.key==="report-defect"){setSelectedAircraftId("");setSelectedAircraft(null);setSelectedAreaId("");setSelectedFormForReport(null);setReportFormAnswers({});setReportFormFiles({});setReportFormReady(false);setDialog(false);}
    if (item.key === "configuration" || parentKey === "configuration") {
      setActiveSection("configuration");
      setConfigurationKey(item.key === "configuration" ? "" : item.section || item.key);
      setCurrentNavKey(item.key === "configuration" ? "configuration" : item.key);
      return;
    }
    setActiveSection("defects");
    setCurrentNavKey(item.key);
    const action = item.section || item.key;
    const defaultFilter: Record<string, string> = { home: "ALL STATUS", defects: "ALL STATUS", inspections: "INSPECTION_REQUIRED", "corrective-actions": "ACTION_ASSIGNED", verification: "AWAITING_VERIFICATION", quality: "AWAITING_VERIFICATION", audit: "CLOSED" };
    setFilter(defaultFilter[action] || "ALL STATUS");
    setArea("ALL AREAS");
    setSearchExecuted(action !== "home");
  }

  function handleNavigationKeyDown(event: ReactKeyboardEvent<HTMLElement>) {
    const target = event.target;
    if (!(target instanceof HTMLElement)) return;
    const trigger = target.closest<HTMLButtonElement>("[data-navigation-trigger]");
    if (trigger && (event.key === "ArrowDown" || event.key === "ArrowUp")) {
      const menuKey = trigger.dataset.navigationTrigger;
      if (!menuKey) return;
      event.preventDefault();
      setOpenNavigationMenu(menuKey);
      window.requestAnimationFrame(() => {
        const entries = navigationRef.current?.querySelectorAll<HTMLButtonElement>(`#navigation-menu-${menuKey} [role='menuitem']`);
        (event.key === "ArrowUp" ? entries?.[entries.length - 1] : entries?.[0])?.focus();
      });
      return;
    }
    if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
    const menu = target.closest<HTMLElement>("[role='menu']");
    if (!menu) return;
    const entries = Array.from(menu.querySelectorAll<HTMLButtonElement>("[role='menuitem']"));
    const index = entries.indexOf(target as HTMLButtonElement);
    if (index < 0) return;
    event.preventDefault();
    entries[(index + (event.key === "ArrowDown" ? 1 : entries.length - 1)) % entries.length]?.focus();
  }

  function renderNavigationItem(item: NavigationItem) {
    const active = currentNavKey === item.key || (item.key === "configuration" && activeSection === "configuration") || item.children.some((child) => child.key === currentNavKey);
    const label = item.key === "home" ? "Defect Summary" : item.key === "audit" ? "Audit Log" : item.label;
    if (!item.children.length) {
      return <button type="button" key={item.key} className={active ? "app-nav-link active" : "app-nav-link"} aria-current={active ? "page" : undefined} onClick={() => openNavigation(item)}><span aria-hidden="true">{item.icon}</span>{label}</button>;
    }
    return <div className={`app-nav-group${item.key === "configuration" ? " administration-nav-group" : ""}`} key={item.key}>
      <button type="button" data-navigation-trigger={item.key} aria-haspopup="true" aria-expanded={openNavigationMenu === item.key} aria-controls={`navigation-menu-${item.key}`} aria-current={active ? "page" : undefined} className={`app-nav-link${active ? " active" : ""}${item.key === "configuration" ? " administration-nav-link" : ""}`} onClick={() => setOpenNavigationMenu((current) => current === item.key ? null : item.key)}>
        <span aria-hidden="true">{item.icon}</span>{label}<span className="app-nav-caret" aria-hidden="true">⌄</span>
      </button>
      {openNavigationMenu === item.key && <div className="app-nav-menu" id={`navigation-menu-${item.key}`} role="menu" aria-label={`${label} menu`}>
        {item.children.map((child) => <button type="button" role="menuitem" key={`${child.key}-${child.section}`} onClick={() => openNavigation(child, item.key)}>{child.label}</button>)}
      </div>}
    </div>;
  }

  async function createDefect(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (saving) return;
    if (!selectedAircraftId || !selectedAircraft || !selectedFormForReport) {
      await alert.warning("Select an aircraft, cabin area, and its published operational form before reporting.");
      return;
    }
    setSaving(true);
    const formElement = event.currentTarget;
    try { defectReportPayload(selectedFormForReport.configuration.fields, reportFormAnswers, areaMasterOptions.find(item=>item.id===selectedAreaId)?.code); }
    catch (error) { await alert.warning(error instanceof Error ? error.message : "Configure the selected report form before saving."); return; }
    const payload = { tailNumber: selectedAircraft.tailNumber, areaId: selectedAreaId, aircraftId: selectedAircraftId, formId: selectedFormForReport.id, formVersionId: selectedFormForReport.versionId, formAnswers: reportFormAnswers };
    try {
      reportIdempotencyKey.current ||= crypto.randomUUID();
      const response = await apiFetch(`${API}/defects`, { method: "POST", headers: { "content-type": "application/json", "Idempotency-Key": reportIdempotencyKey.current }, body: JSON.stringify(payload) });
      if (!response.ok) {
        throw new Error(await responseErrorMessage(response, "Could not create the defect. Check your access and the details."));
      }
      const created = await response.json() as Defect;
      reportIdempotencyKey.current = null; formElement.reset(); setFilter("ALL STATUS"); setArea("ALL AREAS"); closeReportDialog();
      window.dispatchEvent(new Event("aircraft-defect-list-refresh"));
      await load();
      await alert.success(`Defect ${created.reference} reported successfully.`);
    } catch (error) { await alert.error(error instanceof Error ? error.message : "Could not create the defect."); }
    finally { setSaving(false); }
  }

  function navigateToDefect(defectId: string) {
    void openQueueDefect(defectId);
  }

  function selectAircraft(aircraft: FleetAircraft) {
    reportIdempotencyKey.current = null;
    reportModalReturnContext.current = null;
    setSelectedAreaId(""); setSelectedFormForReport(null); setReportFormAnswers({}); setReportFormFiles({}); setReportFormReady(false); setInitialFormForDefect(null); setDialog(false);
    setSelectedAircraft(aircraft);
    setSelectedAircraftId(aircraft.id);
  }

  function leaveAircraft() {
    reportIdempotencyKey.current = null;
    reportModalReturnContext.current = null;
    setSelectedAreaId(""); setSelectedFormForReport(null); setReportFormAnswers({}); setReportFormReady(false); setInitialFormForDefect(null); setDialog(false);
    setSelectedAircraftId(""); setSelectedAircraft(null);
  }

  function startAircraftDefect(aircraft: FleetAircraft, areaId: string, form: SelectedReportForm) {
    reportIdempotencyKey.current = null;
    // Keep the originating aircraft page mounted beneath the modal, but require a fresh aircraft choice for this report.
    reportModalReturnContext.current = { aircraftId: selectedAircraftId, aircraft: selectedAircraft };
    void aircraft; setSelectedAircraft(null); setSelectedAreaId(areaId);
    setSelectedFormForReport(form); setReportFormAnswers({}); setReportFormFiles({}); setReportFormReady(false); setDialog(true);
  }

  function closeReportDialog() {
    const returnContext = reportModalReturnContext.current;
    reportModalReturnContext.current = null;
    setDialog(false);
    setSelectedFormForReport(null);
    setReportFormAnswers({});
    setReportFormFiles({});
    setReportFormReady(false);
    setSelectedAircraft(returnContext?.aircraft ?? null);
    setSelectedAircraftId(returnContext?.aircraftId ?? "");
  }

  async function openDetail(defect: Defect, form?: SelectedReportForm, answers: Record<string, unknown> = {}, files?:Record<string,File[]>) {
    setInitialFormForDefect(form ? { defectId: defect.id, formId: form.id, versionId: form.versionId, answers, files } : null);
    setSelected(defect); setEvents([]);
    try {
      const response = await apiFetch(`${API}/defects/${defect.id}/events`);
      if (response.ok) setEvents(await response.json());
    } catch { /* Keep the defect details available if audit history is temporarily unavailable. */ }
  }

  async function openQueueDefect(defectId: string, aircraftListId?: string) {
    defectReturnScrollY.current = window.scrollY;
    setOpenDefectAircraftListId(aircraftListId || null);
    setOpenDefectId(defectId);
  }

  async function refreshAfterReviewerDecision(defect:Defect,status:string){
    const response=await apiFetch(`${API}/defects/${defect.id}`);
    if(!response.ok)throw new Error(await responseErrorMessage(response,"Decision recorded, but the updated case could not be refreshed."));
    const updated=await response.json() as Defect;
    await openDetail(updated);await load();window.dispatchEvent(new Event("review-queue-refresh"));
    await alert.success(status==="REPORTED"?"Report returned to the Auditor for correction. The original submission and decision remain in the audit history.":"Review approved. Inspection is next; the selected maintenance team will receive work only after a passing inspection.");
  }

  async function refreshAfterClosureDecision(defect:Defect,status:string){
    const response=await apiFetch(`${API}/defects/${defect.id}`);
    if(!response.ok)throw new Error(await responseErrorMessage(response,"Decision recorded, but the updated case could not be refreshed."));
    const updated=await response.json() as Defect;
    await openDetail(updated);await load();window.dispatchEvent(new Event("review-queue-refresh"));window.dispatchEvent(new Event("maintenance-queue-refresh"));
    await alert.success(status==="CLOSED"?"Closure accepted. The defect is now closed.":"Closure declined. The defect has returned to its assigned Maintenance Team.");
  }

  if (!identity) return <AccessStatePage applicationName={applicationName} loading={loading} message={authorizationError || "Access unavailable"} actionLabel={!loading ? "Try again" : undefined} onAction={!loading ? () => void load() : undefined} />;

  return <main className="shell app-shell block min-h-screen w-full max-w-full overflow-x-clip bg-[#eaf1ff]">
    <div className="app-shell-header">
      <header className="app-topbar sticky top-0 z-[5] flex min-h-[70px] items-center gap-[clamp(14px,2vw,38px)] px-[clamp(18px,2.4vw,52px)]">
        <button type="button" className="app-logo app-logo-button" onClick={() => openNavigation({key:"home",label:"Defect Summary",icon:"⌂",section:"home",children:[]})} aria-label={`${identity.applicationName?.trim() || "eCabin Ledger"} Defect Summary`}><span>{(identity.applicationName?.trim() || "eCabin Ledger").charAt(0).toLowerCase()}</span><b>{identity.applicationName?.trim() || "eCabin Ledger"}</b></button>
        <div className="app-organization"><small>ORGANIZATION</small><strong>{identity?.organizationName || "Loading…"}</strong></div>
        <div className="app-profile" ref={profileRef}>
          <NotificationBell request={fleetStatusFetch} onOpenDefect={id => { void openQueueDefect(id); }} open={notificationOpen} onOpenChange={open => { setNotificationOpen(open); if (open) setProfileOpen(false); }} />
          <button type="button" className="app-profile-icon" aria-label="Show profile email" aria-expanded={profileOpen} aria-controls="profile-email-popover" onClick={() => { setNotificationOpen(false); setProfileOpen(open => !open); }} title={localSessionEmail || "Profile email"}>{identity?.displayName.split(/\s+/).map((part) => part[0]).slice(0, 2).join("").toUpperCase() || "—"}</button>
          <span className="app-profile-copy"><b>{identity?.displayName || "Enterprise user"}</b></span>
          {profileOpen && <div className="app-profile-popover" id="profile-email-popover" role="status"><small>EMAIL</small><b>{localSessionEmail || "Email not available"}</b></div>}
          <button className="app-signout" aria-label="Sign out" onClick={() => void confirmLogout()} title="Sign out"><svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M10 17l5-5-5-5M15 12H3"/><path d="M12 3h6a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-6"/></svg></button>
        </div>
      </header>
      <nav ref={navigationRef} className="app-navigation flex min-w-0 flex-1 flex-wrap items-stretch gap-[5px] self-stretch overflow-visible" aria-label="Main navigation" onKeyDown={handleNavigationKeyDown}>
        <div className="app-navigation-items mx-auto flex min-h-[54px] w-max max-w-full items-center gap-[clamp(4px,.7vw,12px)]">{navigation.map(renderNavigationItem)}</div>
      </nav>
    </div>
    <section className="workspace m-0 min-h-[calc(100vh-70px)] w-full min-w-0" id="home">
      {openDefectId ? <DefectDetailsPage defectId={openDefectId} aircraftListId={openDefectAircraftListId} onBack={()=>{setOpenDefectId(null);setOpenDefectAircraftListId(null);requestAnimationFrame(()=>window.scrollTo({top:defectReturnScrollY.current,behavior:"instant"}));}} /> : currentNavKey === "user-login-monitoring" && hasPermission(identity?.permissions, "ADMINISTER_ORGANIZATION") ? <UserLoginMonitoringPage request={configurationFetch} /> : currentNavKey === "api-rate-limits" && hasPermission(identity?.permissions, "ADMINISTER_ORGANIZATION") ? <ApiRateLimitsPage request={configurationFetch} /> : activeSection === "configuration" && hasPermission(identity?.permissions, "ADMINISTER_ORGANIZATION") ? <ConfigurationPage request={configurationFetch} initialKey={configurationKey} /> : currentNavKey === "report-defect" && hasPermission(identity?.permissions,"REPORT") ? <ReportDefectPage request={operationalFormFetch} organizationCacheKey={identity?.organizationName||""} onCreated={()=>{void load();}} onOpenDefect={id=>{void openQueueDefect(id);}} /> : currentNavKey === "home" && selectedAircraftId ? <AircraftDefectListPage request={fleetStatusFetch} aircraftId={selectedAircraftId} organizationCacheKey={identity.organizationName} onBack={leaveAircraft} onLogDefect={startAircraftDefect} onOpenDefect={id => { void openQueueDefect(id, selectedAircraftId); }} canReport={hasPermission(identity?.permissions, "REPORT")} /> : currentNavKey === "home" ? <FleetStatusPage request={fleetStatusFetch} onSelectAircraft={selectAircraft} /> : currentNavKey === "corrective-actions" && hasPermission(identity?.permissions, "PERFORM_ACTION") ? <MaintenanceWorkQueuePage request={fleetStatusFetch} onOpen={(defectId) => { void openQueueDefect(defectId); }} /> : currentNavKey === "review-queue" && (hasPermission(identity?.permissions, "REVIEW") || hasPermission(identity?.permissions, "APPROVE") || hasPermission(identity?.permissions, "ADMINISTER_ORGANIZATION")) ? <ReviewerWorkQueuePage request={fleetStatusFetch} onOpen={(defectId) => { void openQueueDefect(defectId); }} /> : <div className={`content max-w-none min-h-[calc(100vh-70px)] px-[clamp(20px,2.5vw,52px)] py-[clamp(24px,3vw,56px)]${currentNavKey === "reports" ? " reports-page-shell" : ""}`} id="defects">
        <div className="heading"><div><h1>{currentPageLabel}</h1></div></div>
        {currentNavKey === "reports" && !hasPermission(identity?.permissions, "REPORT_VIEW") && (hasPermission(identity?.permissions, "ADMINISTER_ORGANIZATION")
          ? <section className="report-access-restricted" role="status"><h2>Report data access is not granted</h2><p>Your organization administrator access does not automatically include report data access. In Report Master, grant this role View access for the report; running reports also requires Execute access. The matching report permissions must be assigned to this role as well.</p></section>
          : <p role="alert">Your account does not have access to Reports.</p>)}
        {currentNavKey === "reports" && summaryAvailable && <section className="metrics reports-copy-protected grid grid-cols-[repeat(4,minmax(0,1fr))] gap-3 max-[700px]:grid-cols-2" aria-label="Defect summary" onCopy={(event) => event.preventDefault()} onCut={(event) => event.preventDefault()}>
          <article className="metric-card"><div className="metric-head">OPEN DEFECTS <span className="metric-icon green">↗</span></div><strong>{loading ? "—" : summary.openDefects}</strong><div className="metric-foot">Across active fleet <span className="sparkline">▁▃▂▅▃▆▄▇</span></div></article>
          <article className="metric-card"><div className="metric-head">CRITICAL ITEMS <span className="metric-icon red">!</span></div><strong className={summary.criticalDefects ? "critical-number" : ""}>{loading ? "—" : summary.criticalDefects.toString().padStart(2, "0")}</strong><div className="metric-foot"><span className="status-dot amber"></span> Requiring immediate attention</div></article>
          <article className="metric-card"><div className="metric-head">{workflowStatusLabel("REPORTED", workflowStatuses).toUpperCase()} TODAY <span className="metric-icon blue">◷</span></div><strong>{loading ? "—" : summary.reportedToday.toString().padStart(2, "0")}</strong><div className="metric-foot">New cabin findings</div></article>
          <article className="metric-card compliance-card"><div className="metric-head">{workflowStatusLabel("CLOSED", workflowStatuses).toUpperCase()} THIS MONTH <span className="metric-icon green">✓</span></div><strong>{loading ? "—" : summary.closedThisMonth.toString().padStart(2, "0")}</strong><div className="metric-foot"><span className="status-dot green-dot"></span> With audit event history</div></article>
        </section>}
        {currentNavKey === "reports" && hasPermission(identity?.permissions, "REPORT_VIEW") && <DynamicReportViewer request={fleetStatusFetch} permissions={identity?.permissions || []} />}
        {currentNavKey !== "reports" && <section className="register defect-register-data">
          <div className="register-title"><div><div className="eyebrow">LIVE FLEET LOG</div><h2>Defect Register <span className="result-count">{searchExecuted ? items.length : "—"}</span></h2></div></div>
          <form className="register-toolbar" aria-label="Defect register filters" onSubmit={(event) => { event.preventDefault(); setSearchFilters({ ...searchDraft }); setCursor(null); setSearchExecuted(true); }}>
            <label className="register-keyword-search"><span aria-hidden="true">⌕</span><input type="search" value={searchDraft.query} onChange={(event) => setSearchDraft({ ...searchDraft, query: event.target.value })} placeholder="Search defect ID, component, condition…" aria-label="Search defect ID, component, or condition" /></label>
            <label className="register-toolbar-filter"><span>Area</span><select value={area} onChange={(event) => { setArea(event.target.value); setCursor(null); setSearchExecuted(true); }}>{areas.map((value) => <option key={value} value={value}>{value === "ALL AREAS" ? "All areas" : human(value)}</option>)}</select></label>
            <label className="register-toolbar-filter"><span>Status</span><select value={filter} onChange={(event) => { setFilter(event.target.value); setCursor(null); setSearchExecuted(true); }}><option value="ALL STATUS">All statuses</option>{workflowStatuses.filter(value => value.isActive).map((value) => <option key={value.statusCode} value={value.statusCode}>{workflowStatusLabel(value.statusCode, workflowStatuses)}</option>)}</select></label>
            <div className="register-date-filter"><span>Date range</span><details className="register-date-menu"><summary aria-label="Choose a date range"><span className="register-date-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none"><rect x="3.5" y="5" width="17" height="16" rx="2"/><path d="M7.5 3v4M16.5 3v4M4 9.5h16"/></svg></span>{registerDateRangeLabel(searchDraft)}<span className="register-date-chevron" aria-hidden="true">⌄</span></summary><div className="register-date-popover"><label>From date<input type="date" value={searchDraft.fromDate} onChange={(event) => setSearchDraft({ ...searchDraft, fromDate: event.target.value })} /></label><label>To date<input type="date" value={searchDraft.toDate} onChange={(event) => setSearchDraft({ ...searchDraft, toDate: event.target.value })} /></label><button type="submit" className="button-primary">Apply dates</button></div></details></div>
          </form>
          <div className="table-scroll"><table><thead><tr><th>Defect ID</th><th>Area</th><th>Cabin zone</th><th>Component</th><th>Condition</th><th>Severity</th><th>Status</th><th>Case Created Date</th><th>Case Closed Date</th></tr></thead><tbody>
            {searchExecuted && items.map((defect) => <tr key={defect.id} data-search-row={`${defect.title} ${defect.reference} ${defect.tailNumber} ${defect.zone} ${defect.location}`.toLowerCase()} onClick={() => navigateToDefect(defect.id)} tabIndex={0} onKeyDown={(event) => { if (event.key === "Enter") navigateToDefect(defect.id); }}>
              <td><button type="button" className="fleet-aircraft-link" onClick={(event)=>{event.stopPropagation();navigateToDefect(defect.id);}}>{defect.reference}</button></td><td>{defect.location}</td><td>{defect.zone || "—"}</td><td>{defect.component || "—"}</td><td>{defect.conditionName || "—"}</td><td><SeverityBadge request={fleetStatusFetch} severityCode={defect.severity} severityName={defect.severityName} /></td><td><WorkflowStatusBadge statusCode={defect.status} statuses={workflowStatuses} /></td><td className="date">{formatDateTime(defect.reportedAt)}</td><td className="date">{defect.closedAt ? formatDateTime(defect.closedAt) : "—"}</td>
            </tr>)}
            {!searchExecuted && <tr><td className="empty" colSpan={9}><span className="empty-art">⌕</span><b>Search the live fleet log</b><small>Enter search criteria above to find cabin defect records.</small></td></tr>}
            {searchExecuted && !loading && items.length === 0 && <tr><td className="empty" colSpan={9}><span className="empty-art">✧</span><b>No defects in this view</b><small>Try changing your search or date range.</small></td></tr>}
            {searchExecuted && loading && items.length === 0 && <tr><td className="empty" colSpan={9}>Searching the operator’s defect register…</td></tr>}
          </tbody></table></div>
          <div className="table-foot"><span>Showing <b>{items.length}</b> recent records</span>{cursor && <button className="load-more" onClick={() => void load(cursor, true)}>Load more defects <span>↓</span></button>}</div>
        </section>}
      </div>}
    </section>

    {dialog && hasPermission(identity?.permissions, "REPORT") && <DialogPortal onClose={closeReportDialog} labelledBy="modal-title" className="modal report-modal"><div className="modal-heading flex items-start justify-between mb-[18px]"><div><div className="eyebrow">NEW OPERATIONAL RECORD</div><h2 id="modal-title">Log a defect</h2></div><button type="button" className="close" onClick={closeReportDialog} aria-label="Close">×</button></div><section className="report-system-context" aria-label="System-controlled report context"><span>Aircraft registration<b>{selectedAircraft?.tailNumber || "Select in the form below"}</b></span><span>Cabin area<b>{areaMasterOptions.find(item => item.id === selectedAreaId)?.name || "Unavailable"}</b></span></section><form id="defect-report-form" onChange={() => { if(reportIdempotencyKey.current)reportIdempotencyKey.current=null; }} onSubmit={createDefect}>
      {selectedFormForReport && selectedAreaId ? <><OperationalFormRenderer key={`${selectedAreaId}:${selectedFormForReport.id}:${selectedFormForReport.versionId}`} request={operationalFormFetch} areaId={selectedAreaId} areaName={areaMasterOptions.find(item => item.id === selectedAreaId)?.name} areaCode={areaMasterOptions.find(item=>item.id===selectedAreaId)?.code} initialFormId={selectedFormForReport.id} initialVersionId={selectedFormForReport.versionId} preparationMode initialAnswers={reportFormAnswers} onAnswersChange={updateReportFormAnswers} onFilesChange={updateReportFormFiles} onAircraftSelected={option=>{setSelectedAircraft(option?{...option,openDefects:0}:null);setSelectedAircraftId(option?.id||"");}} onPreparationReady={updateReportFormReady} /></> : <p role="alert">Choose a cabin area and published form before reporting.</p>}
      <div className="modal-actions flex justify-end gap-2 border-t border-[#edf0ec] pt-[14px] mt-[13px]"><button type="button" className="button-secondary" onClick={closeReportDialog}>Cancel</button><button className="button-primary" disabled={saving || !selectedFormForReport || !reportFormReady}>{saving ? "Saving…" : "Save defect →"}</button></div>
    </form></DialogPortal>}
    {selected && reassignmentTarget?.activeActionId ? <MaintenanceTeamReassignmentDialog defectId={reassignmentTarget.id} actionId={reassignmentTarget.activeActionId} currentTeam={reassignmentTarget.assignedTeam || null} request={fleetStatusFetch} onClose={() => setReassignmentTarget(null)} onComplete={async () => { setSelected(null); setReassignmentTarget(null); await load(); }} /> : selected && <DialogPortal onClose={() => setSelected(null)} labelledBy="defect-detail-title" className="modal detail-modal"><div className="modal-heading flex items-start justify-between mb-[18px]"><div><div className="eyebrow">{selected.reference} · {selected.tailNumber}</div><h2 id="defect-detail-title">{selected.title}</h2></div><button className="close" onClick={() => setSelected(null)} aria-label="Close">×</button></div><p className="detail-description">{selected.description}</p><div className="detail-grid"><span>Location<b>{human(selected.location)} · {selected.zone}</b></span><span>Condition<b>{selected.conditionName || "Not recorded"}</b></span><span>Severity<SeverityBadge request={fleetStatusFetch} severityCode={selected.severity} severityName={selected.severityName} /></span><span>Status<WorkflowStatusBadge statusCode={selected.status} statuses={workflowStatuses} /></span><span>Reported by<b>{selected.reportedBy}</b></span><span>Assigned to<b>{selected.assignedTo || selected.assignedTeam || "Unassigned"}</b></span></div>{selected.routingAttention&&<p className="configuration-field-error" role="status">{selected.routingAttention}</p>}{hasPermission(identity?.permissions,"REVIEW")&&["REPORTED","UNDER_REVIEW"].includes(selected.status)&&areaMasterOptions.find(item=>item.id===selected.areaId)?.code==="GALLEY"&&<GalleyReviewerDecisionForm key={`${selected.id}:${selected.status}`} request={fleetStatusFetch} defect={selected} onComplete={status=>{void refreshAfterReviewerDecision(selected,status).catch(error=>alert.error(error instanceof Error?error.message:"Decision saved, but the case could not be refreshed."));}}/>}{formPurposeForStatus(selected.status,identity?.permissions) && <OperationalFormRenderer key={`${selected.id}:${formPurposeForStatus(selected.status,identity?.permissions)}:${initialFormForDefect?.formId || ""}:${initialFormForDefect?.versionId || ""}`} request={operationalFormFetch} defectId={selected.id} actionId={selected.activeActionId||undefined} areaId={selected.areaId} areaName={areaMasterOptions.find(item => item.id === selected.areaId)?.name} areaCode={areaMasterOptions.find(item => item.id === selected.areaId)?.code} purpose={formPurposeForStatus(selected.status,identity?.permissions)!} initialFormId={initialFormForDefect?.defectId === selected.id ? initialFormForDefect.formId : undefined} initialVersionId={initialFormForDefect?.defectId === selected.id ? initialFormForDefect.versionId : undefined} initialAnswers={initialFormForDefect?.defectId === selected.id ? initialFormForDefect.answers : undefined} initialFiles={initialFormForDefect?.defectId===selected.id?initialFormForDefect.files:undefined} onWorkflowComplete={status=>{void refreshAfterClosureDecision(selected,status).catch(error=>alert.error(error instanceof Error?error.message:"Decision saved, but the case could not be refreshed."));}}/>}<h3 className="timeline-heading">Audit history</h3><div className="timeline">{events.map((item) => <div className="timeline-item relative flex pb-[13px]" key={item.id}><span className="timeline-dot"></span><div><b>{human(item.eventType)}{item.toStatus ? <> · <WorkflowStatusBadge statusCode={item.toStatus} statuses={workflowStatuses}/></> : ""}{item.inspectionOutcome ? ` · Inspection ${human(item.inspectionOutcome)}` : ""}</b><p>{item.note}{item.evidenceReference ? `\nEvidence: ${item.evidenceReference}` : ""}</p><small>{item.actorId} · {formatDateTime(item.occurredAt)}</small></div></div>)}{!events.length && <span className="timeline-empty">Loading history or no recorded events.</span>}</div><p className="modal-note"><span>✓</span> Form submissions and evidence are retained against the exact published version.</p><div className="modal-actions flex justify-end gap-2 border-t border-[#edf0ec] pt-[14px] mt-[13px]"><button className="button-secondary" onClick={() => setSelected(null)}>Close</button>{canReassignMaintenance(identity?.permissions || [], selected.activeActionId, selected.status) && <ReassignTeamButton onClick={() => setReassignmentTarget(selected)} />}</div></DialogPortal>}
  </main>;
}
