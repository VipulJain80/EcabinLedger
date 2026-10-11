"use client";

import { FormEvent, KeyboardEvent as ReactKeyboardEvent, useCallback, useEffect, useRef, useState } from "react";
import ConfigurationPage from "./configuration";
import FleetStatusPage from "@/common/fleet/FleetStatusPage";
import AircraftDefectListPage from "@/common/fleet/AircraftDefectListPage";
import { FleetAircraft } from "@/common/fleet/FleetStatusPage";
import MaintenanceWorkQueuePage from "@/common/fleet/MaintenanceWorkQueuePage";
import ReviewerWorkQueuePage from "@/common/fleet/ReviewerWorkQueuePage";
import DialogPortal from "@/common/dialogs/DialogPortal";
import OperationalFormRenderer from "@/common/forms/OperationalFormRenderer";
import { formPurposeForStatus } from "@/common/forms/formResolution";
import { defectReportPayload, ReportField } from "@/common/forms/defectReportMapping";
import NotificationBell from "@/common/notifications/NotificationBell";
import ApiRateLimitsPage from "@/common/configuration/ApiRateLimitsPage";
import { alert, responseErrorMessage } from "@/common/alerts";
import { canAdvanceWorkflow, hasPermission } from "@/common/rbac/permissions";

declare global { interface Window { eCabinAuth?: { getAccessToken: () => Promise<string> }; } }

type Defect = {
  id: string; reference: string; tailNumber: string; aircraftType: string; location: string; category: string; zone: string;
  areaId: string;
  title: string; description: string; severity: string; severityName?: string; status: string; reportedBy: string; assignedTo: string | null;
  dueAt: string | null; activeActionId: string | null; conditionName?: string | null; reportedAt: string; updatedAt: string;
};
type Page = { items: Defect[]; nextCursor: string | null };
type Summary = { openDefects: number; criticalDefects: number; reportedToday: number; closedThisMonth: number };
type Identity = { organizationName: string; displayName: string; role: string; permissions: string[] };
type NavigationItem = { key: string; label: string; icon: string; section: string; children: NavigationItem[] };
type SearchFilters = { query: string; aircraftType: string; fromDate: string; toDate: string; mine: boolean };
type MasterOption = { id?: string; code: string; name: string; areaId?: string };
type AuditEvent = { id: string; eventType: string; fromStatus: string | null; toStatus: string | null; note: string; evidenceReference: string | null; inspectionOutcome: string | null; actorId: string; occurredAt: string };
type SelectedReportForm = { id: string; versionId: string; versionNumber: number; name: string; configuration: { fields: ReportField[] } };
const API = process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080/api/v1";
let localDevToken: { value: string; expiresAt: number } | null = null;
let localDevEmail: string | null = null;
const areas = ["ALL AREAS", "CABIN", "GALLEY", "LAVATORY", "ATTENDANT_SEAT"];
const statuses = ["ALL STATUS", "REPORTED", "UNDER_REVIEW", "INSPECTION_REQUIRED", "INSPECTION_IN_PROGRESS", "INSPECTION_COMPLETE", "ACTION_ASSIGNED", "IN_PROGRESS", "AWAITING_VERIFICATION", "VERIFIED", "APPROVAL_REQUIRED", "CLOSED", "REOPENED", "REJECTED", "CANCELLED"];
const human = (value: string) => value.replaceAll("_", " ").toLowerCase().replace(/\b\w/g, (char) => char.toUpperCase());

const aircraftFromBrowserLocation = () => {
  if (typeof window === "undefined") return "";
  return new URLSearchParams(window.location.search).get("aircraft") || window.location.pathname.match(/^\/aircraft\/([^/]+)\/defects\/?$/)?.[1] || "";
};
async function getAccessToken(refreshLocal = false) {
  let token = await window.eCabinAuth?.getAccessToken();
  if (!token && process.env.NODE_ENV === "development" && localDevEmail) {
    if (refreshLocal) localDevToken = null;
    if (!localDevToken || localDevToken.expiresAt <= Date.now() + 30_000) {
      const tokenResponse = await fetch("http://127.0.0.1:8080/api/dev/token", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ email: localDevEmail }),
        cache: "no-store",
      });
      if (!tokenResponse.ok) throw new Error("Local demo access is unavailable. Start the backend with the local-auth profile and apply the demo seed.");
      const issued: { accessToken: string; expiresAt: string } = await tokenResponse.json();
      localDevToken = { value: issued.accessToken, expiresAt: Date.parse(issued.expiresAt) };
    }
    token = localDevToken.value;
  }
  return token;
}

async function apiFetch(path: string, init: RequestInit = {}) {
  let token = await getAccessToken();
  const headers = new Headers(init.headers);
  if (token) headers.set("Authorization", `Bearer ${token}`);
  const request = () => fetch(path, { ...init, headers, credentials: "omit", cache: "no-store" });
  let response = await request();
  if (response.status === 401 && process.env.NODE_ENV === "development" && !window.eCabinAuth) {
    token = await getAccessToken(true);
    if (token) headers.set("Authorization", `Bearer ${token}`);
    response = await request();
  }
  return response;
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
  const [items, setItems] = useState<Defect[]>([]);
  const [filter, setFilter] = useState("ALL STATUS");
  const [area, setArea] = useState("ALL AREAS");
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [dialog, setDialog] = useState(false);
  const [saving, setSaving] = useState(false);
  const reportIdempotencyKey = useRef<string | null>(null);
  const [reviewSubmitting, setReviewSubmitting] = useState(false);
  const [selected, setSelected] = useState<Defect | null>(null);
  const [selectedAircraftId, setSelectedAircraftId] = useState(aircraftFromBrowserLocation);
  const [selectedAircraft, setSelectedAircraft] = useState<FleetAircraft | null>(null);
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
  const [identity, setIdentity] = useState<Identity | null>(null);
  const [authorizationError, setAuthorizationError] = useState("");
  const [routeAccessDenied, setRouteAccessDenied] = useState(false);
  const routeAccessChecked = useRef(false);
  const [navigation, setNavigation] = useState<NavigationItem[]>([]);
  const [openNavigationMenu, setOpenNavigationMenu] = useState<string | null>(null);
  const navigationRef = useRef<HTMLElement | null>(null);
  const profileRef = useRef<HTMLDivElement | null>(null);
  const [profileOpen, setProfileOpen] = useState(false);
  const [notificationOpen, setNotificationOpen] = useState(false);
  const [configurationKey, setConfigurationKey] = useState(() => new URLSearchParams(typeof window === "undefined" ? "" : window.location.search).get("admin") || "");
  const [currentNavKey, setCurrentNavKey] = useState(() => new URLSearchParams(typeof window === "undefined" ? "" : window.location.search).get("admin") || "home");
  const [searchDraft, setSearchDraft] = useState<SearchFilters>({ query: "", aircraftType: "", fromDate: "", toDate: "", mine: true });
  const [searchFilters, setSearchFilters] = useState<SearchFilters>({ query: "", aircraftType: "", fromDate: "", toDate: "", mine: true });
  const [searchBy, setSearchBy] = useState("Defect ID");
  const [appliedSearchBy, setAppliedSearchBy] = useState("DEFECT_ID");
  const [searchExecuted, setSearchExecuted] = useState(false);
  const [areaMasterOptions, setAreaMasterOptions] = useState<MasterOption[]>([]);
  const [selectedAreaId, setSelectedAreaId] = useState("");
  const [aircraftTypes, setAircraftTypes] = useState<string[]>([]);
  const [localSessionEmail, setLocalSessionEmail] = useState<string | null>(null);
  const [gatewayAuthAvailable, setGatewayAuthAvailable] = useState(false);
  const [loginEmail, setLoginEmail] = useState("");
  const [loginError, setLoginError] = useState("");
  const [loginLoading, setLoginLoading] = useState(false);
  const [activeSection, setActiveSection] = useState(() => new URLSearchParams(typeof window === "undefined" ? "" : window.location.search).has("admin") ? "configuration" : "defects");
  const currentPageLabel = navigation.flatMap((item) => [item, ...item.children]).find((item) => item.key === currentNavKey)?.label || (currentNavKey === "home" ? "Defect Summary" : "Defect register");

  useEffect(() => { const title = selectedAircraftId ? "Aircraft Defect List" : currentPageLabel; document.title = `${title} | eCabin Ledger`; }, [currentPageLabel, selectedAircraftId]);

  useEffect(() => {
    const restore = () => setSelectedAircraftId(aircraftFromBrowserLocation());
    window.addEventListener("popstate", restore);
    return () => window.removeEventListener("popstate", restore);
  }, []);

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

  useEffect(() => {
    const frame = window.requestAnimationFrame(() => setGatewayAuthAvailable(Boolean(window.eCabinAuth)));
    return () => window.cancelAnimationFrame(frame);
  }, []);

  const load = useCallback(async (after: string | null = null, append = false) => {
    setLoading(true);
    const params = new URLSearchParams({ limit: "25" });
    if (filter !== "ALL STATUS") params.set("status", filter);
    if (area !== "ALL AREAS") params.set("location", area);
    if (searchFilters.query.trim()) params.set("q", searchFilters.query.trim());
    if (searchFilters.query.trim()) params.set("searchBy", appliedSearchBy);
    if (searchFilters.aircraftType) params.set("aircraftType", searchFilters.aircraftType);
    if (searchFilters.fromDate) params.set("fromDate", `${searchFilters.fromDate}T00:00:00Z`);
    if (searchFilters.toDate) params.set("toDate", `${searchFilters.toDate}T00:00:00Z`);
    if (searchFilters.mine) params.set("mine", "true");
    if (append && after) params.set("cursor", after);
    try {
      const [response, summaryResponse, identityResponse, navigationResponse] = await Promise.all([apiFetch(`${API}/defects?${params}`), apiFetch(`${API}/dashboard/summary`), apiFetch(`${API}/me`), apiFetch(`${API}/navigation`)]);
      const responses = [response, summaryResponse, identityResponse, navigationResponse];
      const rejectedResponse = responses.find((candidate) => candidate.status === 401 || candidate.status === 403);
      if (rejectedResponse) {
        const expired = rejectedResponse.status === 401;
        setIdentity(null);
        setNavigation([]);
        setItems([]);
        setSummary({ openDefects: 0, criticalDefects: 0, reportedToday: 0, closedThisMonth: 0 });
        setSelected(null);
        setEvents([]);
        setRouteAccessDenied(false);
        routeAccessChecked.current = false;
        if (expired && localSessionEmail) {
          localDevEmail = null;
          localDevToken = null;
          setLocalSessionEmail(null);
        }
        throw new Error(expired
          ? "Your session has expired. Sign in through your organization’s enterprise access gateway."
          : "Your account is not authorized for this organization or application page. Contact your organization administrator.");
      }
      if (!response.ok || !summaryResponse.ok || !identityResponse.ok || !navigationResponse.ok) throw new Error("We couldn’t load the defect register. Try again.");
      const [data, summaryData, identityData, navigationData]: [Page, Summary, Identity, NavigationItem[]] = await Promise.all([response.json(), summaryResponse.json(), identityResponse.json(), navigationResponse.ok ? navigationResponse.json() : Promise.resolve([])]);
      setSummary(summaryData);
      setIdentity(identityData);
      setNavigation(navigationData);
      setAuthorizationError("");
      if (!routeAccessChecked.current) {
        const requestedConfiguration = new URLSearchParams(window.location.search).get("admin");
        const accessibleItems = navigationData.flatMap((item) => [item, ...item.children]);
        const accessibleKeys = new Set(accessibleItems.map((item) => item.key));
        const requestedKey = requestedConfiguration || "home";
        if (accessibleKeys.has(requestedKey)) {
          const isConfiguration = requestedKey === "configuration" || Boolean(requestedConfiguration);
          setActiveSection(isConfiguration ? "configuration" : "defects");
          setCurrentNavKey(requestedKey);
          if (isConfiguration) setConfigurationKey(requestedKey === "configuration" ? "" : requestedKey);
        } else {
          const fallback = accessibleKeys.has("home") ? "home" : accessibleItems.find((item) => item.children.length === 0)?.key;
          if (requestedConfiguration) {
            setAuthorizationError("Your account does not have access to this application page. Contact your organization administrator.");
            setRouteAccessDenied(true);
          } else if (fallback) {
            setActiveSection("defects");
            setCurrentNavKey(fallback);
            setConfigurationKey("");
          } else {
            setAuthorizationError("Your account does not have access to an application page. Contact your organization administrator.");
          }
        }
        routeAccessChecked.current = true;
      }
      const referenceResponse = await apiFetch(`${API}/reference-data`);
      if (referenceResponse.ok) {
        const referenceData: { areas: MasterOption[]; aircraftTypes: string[] } = await referenceResponse.json();
        setAreaMasterOptions(referenceData.areas);
        setAircraftTypes(referenceData.aircraftTypes);
      }
      setItems((previous) => append ? [...previous, ...data.items] : data.items);
      setCursor(data.nextCursor);
    } catch (error) { const message = error instanceof Error ? error.message : "The service is unavailable."; setAuthorizationError(message); await alert.error(message); }
    finally { setLoading(false); }
  }, [area, appliedSearchBy, filter, localSessionEmail, searchFilters, setSelected]);

  useEffect(() => {
    if (process.env.NODE_ENV === "development" && !localSessionEmail && !gatewayAuthAvailable) {
      return;
    }
    let cancelled = false;
    void Promise.resolve().then(() => { if (!cancelled) return load(); });
    return () => { cancelled = true; };
  }, [gatewayAuthAvailable, load, localSessionEmail]);

  async function loginLocal(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const email = loginEmail.trim().toLowerCase();
    setLoginError("");
    if (window.location.hostname !== "localhost" && window.location.hostname !== "127.0.0.1") {
      setLoginError("Local demo sign-in works only on this computer. Open http://localhost:3000, then sign in again.");
      return;
    }
    setLoginLoading(true);
    try {
      const response = await fetch("http://127.0.0.1:8080/api/dev/token", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ email }),
        cache: "no-store",
      });
      if (!response.ok) {
        setLoginError(response.status === 403
          ? "Choose one of the configured local test identities with an active demo membership."
          : "Could not sign in. Check that the backend is running with the local demo profile.");
        return;
      }
      const issued: { accessToken: string; expiresAt: string } = await response.json();
      localDevEmail = email;
      localDevToken = { value: issued.accessToken, expiresAt: Date.parse(issued.expiresAt) };
      setLocalSessionEmail(email);
      setActiveSection("defects");
      setCurrentNavKey("home");
    } catch {
      setLoginError("Could not connect to the backend. Make sure it is running, then try again.");
    } finally {
      setLoginLoading(false);
    }
  }

  function logoutLocal() {
    localDevEmail = null;
    localDevToken = null;
    setLocalSessionEmail(null);
    setLoginEmail("");
    setLoginError("");
    setIdentity(null);
    setActiveSection("defects");
    setCurrentNavKey("home");
    setNavigation([]);
    setRouteAccessDenied(false);
    setItems([]);
    setSummary({ openDefects: 0, criticalDefects: 0, reportedToday: 0, closedThisMonth: 0 });
  }

  async function confirmLogout() {
    if (await alert.confirm({ title: "Sign out?", text: `You will leave the ${identity?.organizationName || "current"} workspace.`, confirmText: "Sign out" })) logoutLocal();
  }

  function openNavigation(item: NavigationItem, parentKey?: string) {
    setOpenNavigationMenu(null);
    if (item.key === "home") leaveAircraft();
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
      reportIdempotencyKey.current = null; setDialog(false); formElement.reset(); setFilter("ALL STATUS"); setArea("ALL AREAS");
      await load();
      await alert.success(`Defect ${created.reference} reported successfully. Complete the published area form to add the report details.`);
      await openDetail(created, selectedFormForReport || undefined, reportFormAnswers, reportFormFiles);
      setSelectedFormForReport(null);
      setReportFormAnswers({});
      setReportFormFiles({});
    } catch (error) { await alert.error(error instanceof Error ? error.message : "Could not create the defect."); }
    finally { setSaving(false); }
  }

  function openDefectForm() {
    reportIdempotencyKey.current = null;
    setSelectedAreaId(""); setSelectedFormForReport(null); setReportFormAnswers({}); setReportFormFiles({}); setReportFormReady(false);
    if (!selectedAircraftId) {
      setActiveSection("defects"); setCurrentNavKey("home");
      void alert.info("Choose an aircraft from Defect Summary, then select a cabin area and its published form to report a defect.");
      return;
    }
    void alert.info("Choose a cabin area and its published form from the aircraft defect list to start a report.");
  }

  function selectAircraft(aircraft: FleetAircraft) {
    reportIdempotencyKey.current = null;
    setSelectedAreaId(""); setSelectedFormForReport(null); setReportFormAnswers({}); setReportFormFiles({}); setReportFormReady(false); setInitialFormForDefect(null); setDialog(false);
    setSelectedAircraft(aircraft);
    setSelectedAircraftId(aircraft.id);
    window.history.pushState({ aircraft: aircraft.id }, "", `/aircraft/${encodeURIComponent(aircraft.id)}/defects`);
  }

  function leaveAircraft() {
    reportIdempotencyKey.current = null;
    setSelectedAreaId(""); setSelectedFormForReport(null); setReportFormAnswers({}); setReportFormReady(false); setInitialFormForDefect(null); setDialog(false);
    setSelectedAircraftId(""); setSelectedAircraft(null);
    window.history.pushState({}, "", "/");
  }

  function startAircraftDefect(aircraft: FleetAircraft, areaId: string, form: SelectedReportForm) {
    reportIdempotencyKey.current = null;
    setSelectedAircraft(aircraft); setSelectedAircraftId(aircraft.id); setSelectedAreaId(areaId);
    setSelectedFormForReport(form); setReportFormAnswers({}); setReportFormFiles({}); setReportFormReady(false); setDialog(true);
  }

  async function advance(defect: Defect) {
    try {
      let response: Response;
      const json = { "content-type": "application/json" };
      if (["REPORTED", "UNDER_REVIEW", "INSPECTION_REQUIRED", "VERIFIED", "CLOSED"].includes(defect.status)) {
        const next: Record<string, string> = { REPORTED: "UNDER_REVIEW", UNDER_REVIEW: "INSPECTION_REQUIRED", INSPECTION_REQUIRED: "INSPECTION_IN_PROGRESS", VERIFIED: "APPROVAL_REQUIRED", CLOSED: "REOPENED" };
        const status = next[defect.status];
        const note = await alert.prompt({ title: `Add an audit reason for ${human(status)}`, placeholder: "Reason", required: true }); if (!note) return;
        response = await apiFetch(`${API}/defects/${defect.id}/transitions`, { method: "POST", headers: json, body: JSON.stringify({ status, note }) });
      } else if (defect.status === "INSPECTION_IN_PROGRESS") {
        const result = (await alert.prompt({ title: "Inspection result", text: "Enter PASS, FAIL, CONDITIONAL or REQUIRES_FOLLOW_UP", initialValue: "PASS" }))?.toUpperCase(); if (!result) return;
        const findings = await alert.prompt({ title: "Record inspection findings", required: true }); if (!findings) return;
        response = await apiFetch(`${API}/defects/${defect.id}/inspections`, { method: "POST", headers: json, body: JSON.stringify({ result, findings, notes: "" }) });
      } else if (defect.status === "INSPECTION_COMPLETE") {
        const description = await alert.prompt({ title: "Describe the corrective action", required: true }); if (!description) return;
        const assignedUserId = await alert.prompt({ title: "Assign to enterprise user ID", text: "Leave blank to assign a team.", required: false });
        const assignedTeam = assignedUserId ? "" : await alert.prompt({ title: "Responsible maintenance team", required: true });
        if (!assignedUserId && !assignedTeam) return;
        response = await apiFetch(`${API}/defects/${defect.id}/actions`, { method: "POST", headers: json, body: JSON.stringify({ description, assignedUserId, assignedTeam, priority: defect.severity, dueAt: defect.dueAt }) });
      } else if (defect.status === "ACTION_ASSIGNED" && defect.activeActionId) {
        response = await apiFetch(`${API}/defects/${defect.id}/actions/${defect.activeActionId}/start`, { method: "POST" });
      } else if (defect.status === "IN_PROGRESS" && defect.activeActionId) {
        const completionNotes = await alert.prompt({ title: "Record completed corrective action", required: true }); if (!completionNotes) return;
        response = await apiFetch(`${API}/defects/${defect.id}/actions/${defect.activeActionId}/complete`, { method: "POST", headers: json, body: JSON.stringify({ completionNotes }) });
      } else if (defect.status === "AWAITING_VERIFICATION") {
        const result = (await alert.prompt({ title: "Verification result", text: "Enter PASS or FAIL", initialValue: "PASS" }))?.toUpperCase(); if (!result) return;
        const notes = await alert.prompt({ title: "Record verification notes", required: true }); if (!notes) return;
        const evidenceReference = await alert.prompt({ title: "Verification evidence reference", required: true }); if (!evidenceReference) return;
        response = await apiFetch(`${API}/defects/${defect.id}/verifications`, { method: "POST", headers: json, body: JSON.stringify({ result, notes, evidenceReference }) });
      } else if (defect.status === "APPROVAL_REQUIRED") {
        const reason = await alert.prompt({ title: "Approval reason", required: true }); if (!reason) return;
        const evidenceReference = await alert.prompt({ title: "Closure evidence reference", required: true }); if (!evidenceReference) return;
        response = await apiFetch(`${API}/defects/${defect.id}/approvals`, { method: "POST", headers: json, body: JSON.stringify({ decision: "APPROVED", reason, evidenceReference }) });
      } else return;
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not update defect status."));
      await load();
      await alert.success("Defect workflow updated.");
    } catch (error) { await alert.error(error instanceof Error ? error.message : "Could not update defect status."); }
  }

  async function openDetail(defect: Defect, form?: SelectedReportForm, answers: Record<string, unknown> = {}, files?:Record<string,File[]>) {
    setInitialFormForDefect(form ? { defectId: defect.id, formId: form.id, versionId: form.versionId, answers, files } : null);
    setSelected(defect); setEvents([]);
    try {
      const response = await apiFetch(`${API}/defects/${defect.id}/events`);
      if (response.ok) setEvents(await response.json());
    } catch { /* Keep the defect details available if audit history is temporarily unavailable. */ }
  }

  async function openQueueDefect(defectId: string) {
    try {
      const response = await apiFetch(`${API}/defects/${encodeURIComponent(defectId)}`);
      if (!response.ok) throw new Error(await responseErrorMessage(response, "This defect is no longer assigned to one of your active teams."));
      await openDetail(await response.json() as Defect);
    } catch (error) { await alert.error(error instanceof Error ? error.message : "Could not open this defect."); }
  }

  async function reassignDefect(defect: Defect) {
    if (!defect.activeActionId) return;
    try {
      const assignedTeam = await alert.prompt({ title: "Reassign maintenance team", text: `Current assignment: ${defect.assignedTo || "Unassigned"}. Enter the exact active team name.`, placeholder: "Maintenance team", required: true });
      if (!assignedTeam) return;
      const assignedUserId = await alert.prompt({ title: "Optional individual owner", text: "Leave blank to keep the action shared with active members of the team.", placeholder: "Enterprise user ID", required: false });
      const reason = await alert.prompt({ title: "Reassignment reason", required: true });
      if (!reason) return;
      const response = await apiFetch(`${API}/defects/${defect.id}/actions/${defect.activeActionId}/reassign`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ assignedTeam, assignedUserId, reason }) });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not reassign this corrective action."));
      setSelected(null);
      await load();
      await alert.success("Corrective action reassigned. The former team no longer has current-work access.");
    } catch (error) { await alert.error(error instanceof Error ? error.message : "Could not reassign this corrective action."); }
  }

  async function reassignReviewer(defect: Defect) {
    try {
      const teamsResponse=await apiFetch(`${API}/reviewer-teams`);
      if(!teamsResponse.ok)throw new Error(await responseErrorMessage(teamsResponse,"Could not load eligible Reviewer teams."));
      const teams=await teamsResponse.json() as Array<{id:string;name:string;eligibleMembers:number}>;
      if(!teams.length)throw new Error("No active Reviewer team has an active member with REVIEW permission.");
      const teamName=await alert.prompt({title:"Route defect to Reviewer team",text:`Eligible teams: ${teams.map(team=>`${team.name} (${team.eligibleMembers})`).join(", ")}`,placeholder:"Reviewer team name",required:true});
      if(!teamName)return;
      const team=teams.find(item=>item.name.toLocaleLowerCase()===teamName.trim().toLocaleLowerCase());
      if(!team)throw new Error("Choose one of the eligible Reviewer teams listed in the prompt.");
      const reason=await alert.prompt({title:"Reviewer routing reason",required:true});if(!reason)return;
      const response=await apiFetch(`${API}/defects/${defect.id}/review-routing`,{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({teamId:team.id,reason})});
      if(!response.ok)throw new Error(await responseErrorMessage(response,"Could not update Reviewer routing."));
      const updated=await response.json() as Defect;setSelected(updated);await openDetail(updated);await load();await alert.success(`Defect routed to ${team.name}.`);
    } catch(error){await alert.error(error instanceof Error?error.message:"Could not update Reviewer routing.");}
  }

  async function submitReviewDecision(defect:Defect,decision:"APPROVE"|"REJECT") {
    if(reviewSubmitting)return;
    const reason=await alert.prompt({title:decision==="APPROVE"?"Approve defect review":"Reject defect review",text:decision==="APPROVE"?"Approval records the decision and advances the defect to mandatory inspection. Maintenance work remains deferred until inspection passes.":"A rejection reason is required. The defect will remain in the audit history and will not be assigned to maintenance.",placeholder:decision==="REJECT"?"Required rejection reason":"Review rationale",required:true});
    if(!reason?.trim())return;
    setReviewSubmitting(true);
    try{
      const response=await apiFetch(`${API}/defects/${defect.id}/review-decision`,{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({decision,reason:reason.trim()})});
      if(!response.ok)throw new Error(await responseErrorMessage(response,"Could not record the Reviewer decision."));
      const result=await response.json() as {id:string;status:string};
      const refreshed=await apiFetch(`${API}/defects/${defect.id}`);
      if(!refreshed.ok)throw new Error(await responseErrorMessage(refreshed,"Decision recorded, but the updated defect could not be refreshed."));
      const updated=await refreshed.json() as Defect;
      await openDetail(updated);await load();
      await alert.success(decision==="APPROVE"?`Review approved. ${human(result.status)} is next; Cabin Maintenance receives an action after a passing inspection.`:"Review rejected. The decision is recorded and no maintenance action was assigned.");
    }catch(error){await alert.error(error instanceof Error?error.message:"Could not record the Reviewer decision.");}
    finally{setReviewSubmitting(false);}
  }

  if (process.env.NODE_ENV === "development" && !localSessionEmail && !gatewayAuthAvailable) return <main className="login-shell">
    <header className="login-airline-brand" aria-label="Skyways Service"><span>SKYWAYS</span><strong>Service</strong></header>
    <section className="login-card" aria-labelledby="login-title">
      <div className="login-user-icon" aria-hidden="true"><svg viewBox="0 0 56 56"><circle cx="28" cy="17" r="9"/><path d="M10 43c0-8 7-12 18-12s18 4 18 12c0 5-7 7-18 7s-18-2-18-7Z"/></svg></div>
      <h1 id="login-title">LOGIN</h1>
      <form className="login-form" onSubmit={loginLocal}>
        <label className="login-email-label" htmlFor="login-email">Work email</label>
        <input id="login-email" type="email" autoComplete="email" autoFocus required list="local-test-identities" placeholder="Enter or select a local test email" value={loginEmail} onChange={(event) => setLoginEmail(event.target.value)} />
        <datalist id="local-test-identities">
          <option value="abc@gmail.com" />
          <option value="admin.test@ecabin.local" />
          <option value="auditor.test@ecabin.local" />
          <option value="reviewer.test@ecabin.local" />
          <option value="maintenance.test@ecabin.local" />
        </datalist>
        {loginError && <p className="login-error" role="alert">{loginError}</p>}
        <button type="submit" className="login-submit" disabled={loginLoading}>{loginLoading ? "Signing in…" : "Continue with email"}<span aria-hidden="true">→</span></button>
      </form>
      <p className="login-help">Local test identities: <b>abc@gmail.com</b>, admin.test@ecabin.local, auditor.test@ecabin.local, reviewer.test@ecabin.local, maintenance.test@ecabin.local</p>
    </section>
    <p className="login-footer">© 2026 Skyways Service. All Rights Reserved.</p>
  </main>;

  if (routeAccessDenied) return <main className="authorization-gate" role="alert" aria-live="polite">
    <section><span className="eyebrow">ECABIN LEDGER ACCESS</span><h1>Access denied</h1><p>{authorizationError}</p><button type="button" className="button-primary" onClick={() => { setRouteAccessDenied(false); setAuthorizationError(""); setConfigurationKey(""); setActiveSection("defects"); setCurrentNavKey("home"); window.history.replaceState({}, "", window.location.pathname); }}>Go to Defect Summary</button></section>
  </main>;

  if (!identity) return <main className="authorization-gate" role={loading ? "status" : "alert"} aria-live="polite">
    <section><span className="eyebrow">ECABIN LEDGER ACCESS</span><h1>{loading ? "Verifying access…" : "Access unavailable"}</h1><p>{loading ? "Checking your enterprise identity and organization permissions." : authorizationError || "We could not verify your application access."}</p>{!loading && <button type="button" className="button-primary" onClick={() => void load()}>Try again</button>}</section>
  </main>;

  return <main className="shell app-shell">
    <div className="app-shell-header">
      <header className="app-topbar">
        <a className="app-logo" href="#home" aria-label="eCabin Ledger Defect Summary"><span>e</span><b>eCabin Ledger</b></a>
        <div className="app-organization"><small>ORGANIZATION</small><strong>{identity?.organizationName || "Loading…"}</strong></div>
        <div className="app-profile" ref={profileRef}>
          <NotificationBell request={fleetStatusFetch} onOpenDefect={id => { void openQueueDefect(id); }} open={notificationOpen} onOpenChange={open => { setNotificationOpen(open); if (open) setProfileOpen(false); }} />
          <button type="button" className="app-profile-icon" aria-label="Show profile email" aria-expanded={profileOpen} aria-controls="profile-email-popover" onClick={() => { setNotificationOpen(false); setProfileOpen(open => !open); }} title={localSessionEmail || "Profile email"}>{identity?.displayName.split(/\s+/).map((part) => part[0]).slice(0, 2).join("").toUpperCase() || "—"}</button>
          <span className="app-profile-copy"><b>{identity?.displayName || "Enterprise user"}</b></span>
          {profileOpen && <div className="app-profile-popover" id="profile-email-popover" role="status"><small>EMAIL</small><b>{localSessionEmail || "Email not available"}</b></div>}
          {localSessionEmail && <button className="app-signout" aria-label="Sign out" onClick={() => void confirmLogout()} title="Sign out"><svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M10 17l5-5-5-5M15 12H3"/><path d="M12 3h6a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-6"/></svg></button>}
        </div>
      </header>
      <nav ref={navigationRef} className="app-navigation" aria-label="Main navigation" onKeyDown={handleNavigationKeyDown}>
        <div className="app-navigation-items">{navigation.map(renderNavigationItem)}</div>
      </nav>
    </div>
    <section className="workspace" id="home">
      {currentNavKey === "api-rate-limits" && hasPermission(identity?.permissions, "ADMINISTER_ORGANIZATION") ? <ApiRateLimitsPage request={configurationFetch} /> : activeSection === "configuration" && hasPermission(identity?.permissions, "ADMINISTER_ORGANIZATION") ? <ConfigurationPage key={configurationKey} request={configurationFetch} initialKey={configurationKey} /> : currentNavKey === "home" && selectedAircraftId ? <AircraftDefectListPage request={fleetStatusFetch} aircraftId={selectedAircraftId} organizationCacheKey={identity.organizationName} onBack={leaveAircraft} onLogDefect={startAircraftDefect} onOpenDefect={id => { void openQueueDefect(id); }} canReport={hasPermission(identity?.permissions, "REPORT")} /> : currentNavKey === "home" ? <FleetStatusPage request={fleetStatusFetch} onSelectAircraft={selectAircraft} /> : currentNavKey === "corrective-actions" && hasPermission(identity?.permissions, "PERFORM_ACTION") ? <MaintenanceWorkQueuePage request={fleetStatusFetch} onOpen={(defectId) => { void openQueueDefect(defectId); }} /> : currentNavKey === "review-queue" && (hasPermission(identity?.permissions, "REVIEW") || hasPermission(identity?.permissions, "ADMINISTER_ORGANIZATION")) ? <ReviewerWorkQueuePage request={fleetStatusFetch} onOpen={(defectId) => { void openQueueDefect(defectId); }} /> : <div className="content" id="defects">
        <div className="heading"><div><div className="eyebrow"><span></span> CABIN OPERATIONS <em>·</em> FLEET OVERVIEW</div><h1>{currentPageLabel}</h1><p>Search and track cabin findings from initial report through verified closure.</p></div>{hasPermission(identity?.permissions, "REPORT") && <button className="button-primary" onClick={openDefectForm}><span>＋</span> Log a defect</button>}</div>
        <form className="operational-search" onSubmit={(event) => { event.preventDefault(); setSearchFilters({ ...searchDraft }); setAppliedSearchBy(({ "Defect ID": "DEFECT_ID", "Aircraft registration": "AIRCRAFT_REGISTRATION", Category: "CATEGORY", Component: "COMPONENT", "Assigned person": "ASSIGNED_USER" })[searchBy] || "DEFECT_ID"); setCursor(null); setSearchExecuted(true); }}>
          <label>Search by<div className="search-control-row"><select value={searchBy} onChange={(event) => setSearchBy(event.target.value)} aria-label="Search field"><option>Defect ID</option><option>Aircraft registration</option><option>Category</option><option>Component</option><option>Assigned person</option></select><input value={searchDraft.query} onChange={(event) => setSearchDraft({ ...searchDraft, query: event.target.value })} placeholder={`Enter ${searchBy.toLowerCase()}`} /></div></label>
          <label>Aircraft type<select value={searchDraft.aircraftType} onChange={(event) => setSearchDraft({ ...searchDraft, aircraftType: event.target.value })}><option value="">All types</option>{aircraftTypes.map((type) => <option key={type} value={type}>{type}</option>)}</select></label>
          <label>From date<input type="date" value={searchDraft.fromDate} onChange={(event) => setSearchDraft({ ...searchDraft, fromDate: event.target.value })} /></label>
          <label>To date<input type="date" value={searchDraft.toDate} onChange={(event) => setSearchDraft({ ...searchDraft, toDate: event.target.value })} /></label>
          <label className="my-cases">My cases<input type="checkbox" checked={searchDraft.mine} onChange={(event) => setSearchDraft({ ...searchDraft, mine: event.target.checked })} /></label>
          <div className="search-actions"><button className="button-primary" type="submit"><span>⌕</span> Search</button><button type="button" className="button-secondary" onClick={() => { const cleared = { query: "", aircraftType: "", fromDate: "", toDate: "", mine: false }; setSearchDraft(cleared); setSearchFilters(cleared); setSearchBy("Defect ID"); setAppliedSearchBy("DEFECT_ID"); setFilter("ALL STATUS"); setArea("ALL AREAS"); setCursor(null); setSearchExecuted(false); }}>× &nbsp; Clear all</button></div>
        </form>
        {currentNavKey === "reports" && <section className="metrics" aria-label="Defect summary">
          <article className="metric-card"><div className="metric-head">OPEN DEFECTS <span className="metric-icon green">↗</span></div><strong>{loading ? "—" : summary.openDefects}</strong><div className="metric-foot">Across active fleet <span className="sparkline">▁▃▂▅▃▆▄▇</span></div></article>
          <article className="metric-card"><div className="metric-head">CRITICAL ITEMS <span className="metric-icon red">!</span></div><strong className={summary.criticalDefects ? "critical-number" : ""}>{loading ? "—" : summary.criticalDefects.toString().padStart(2, "0")}</strong><div className="metric-foot"><span className="status-dot amber"></span> Requiring immediate attention</div></article>
          <article className="metric-card"><div className="metric-head">REPORTED TODAY <span className="metric-icon blue">◷</span></div><strong>{loading ? "—" : summary.reportedToday.toString().padStart(2, "0")}</strong><div className="metric-foot">New cabin findings</div></article>
          <article className="metric-card compliance-card"><div className="metric-head">CLOSED THIS MONTH <span className="metric-icon green">✓</span></div><strong>{loading ? "—" : summary.closedThisMonth.toString().padStart(2, "0")}</strong><div className="metric-foot"><span className="status-dot green-dot"></span> With audit event history</div></article>
        </section>}
        <section className="register">
          <div className="register-title"><div><div className="eyebrow">LIVE FLEET LOG</div><h2>{currentPageLabel} <span className="result-count">{searchExecuted ? items.length : "—"}</span></h2></div><div className="register-tools">{searchExecuted && <button className="filter-button" onClick={() => setFilter(filter === "ALL STATUS" ? "REPORTED" : "ALL STATUS")}>☷ <span>Filter</span></button>}</div></div>
          {searchExecuted && <div className="filter-row"><div className="chips" role="group" aria-label="Filter by status">{statuses.slice(0, 5).map((value) => <button key={value} className={filter === value ? "chip active" : "chip"} onClick={() => { setFilter(value); setSearchExecuted(true); }}>{value === "ALL STATUS" ? "All status" : human(value)}</button>)}</div><label className="area-filter"><span>AREA</span><select value={area} onChange={(event) => { setArea(event.target.value); setSearchExecuted(true); }} aria-label="Filter by area">{areas.map((value) => <option key={value} value={value}>{human(value)}</option>)}</select></label></div>}
          <div className="table-scroll"><table><thead><tr><th>DEFECT / AIRCRAFT</th><th>LOCATION</th><th>SEVERITY</th><th>STATUS</th><th>REPORTED</th><th>OWNER</th><th></th></tr></thead><tbody>
            {searchExecuted && items.map((defect) => <tr key={defect.id} data-search-row={`${defect.title} ${defect.reference} ${defect.tailNumber} ${defect.zone} ${defect.location}`.toLowerCase()} onClick={() => void openDetail(defect)} tabIndex={0} onKeyDown={(event) => { if (event.key === "Enter") void openDetail(defect); }}>
              <td><div className="defect-name"><span className="defect-icon">{defect.location === "GALLEY" ? "▤" : defect.location === "LAVATORY" ? "◉" : "⌂"}</span><span><b>{defect.title}</b><small>{defect.reference} <i>·</i> {defect.tailNumber} · {defect.zone}</small></span></div></td>
              <td><span className="location-tag">{human(defect.location)}</span></td><td><span className={`severity ${defect.severity.toLowerCase()}`}><i></i>{defect.severityName || human(defect.severity)}</span></td><td><span className={`status ${defect.status.toLowerCase()}`}>{human(defect.status)}</span></td><td className="date">{new Intl.DateTimeFormat(undefined, { month: "short", day: "2-digit" }).format(new Date(defect.reportedAt))}<small>{new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit" }).format(new Date(defect.reportedAt))}</small></td><td><span className="owner"><i>{defect.assignedTo ? defect.assignedTo.split(" ").map((name) => name[0]).slice(0, 2).join("") : "—"}</i>{defect.assignedTo || "Unassigned"}</span></td><td>{canAdvanceWorkflow(identity?.permissions, defect.status) && <button className="row-more" aria-label={`Advance ${defect.reference}`} title="Advance workflow" onClick={(event) => { event.stopPropagation(); void advance(defect); }}>···</button>}</td>
            </tr>)}
            {!searchExecuted && <tr><td className="empty" colSpan={7}><span className="empty-art">⌕</span><b>No search performed</b><small>Enter your search criteria above to find cabin defect records.</small></td></tr>}
            {searchExecuted && !loading && items.length === 0 && <tr><td className="empty" colSpan={7}><span className="empty-art">✧</span><b>No defects in this view</b><small>Try another search, or log a newly discovered cabin defect.</small>{hasPermission(identity?.permissions, "REPORT") && <button onClick={openDefectForm}>Log a defect <span>→</span></button>}</td></tr>}
            {searchExecuted && loading && items.length === 0 && <tr><td className="empty" colSpan={7}>Searching the operator’s defect register…</td></tr>}
          </tbody></table></div>
          <div className="table-foot"><span>Showing <b>{items.length}</b> recent records</span>{cursor && <button className="load-more" onClick={() => void load(cursor, true)}>Load more defects <span>↓</span></button>}<span className="audit-note"><i>✓</i> Changes are recorded in the audit trail</span></div>
        </section>
        <footer><span>eCabin Ledger <i>·</i> Operational recordkeeping</span><span>UTC <i>·</i> Data synced just now</span></footer>
      </div>}
    </section>

    {dialog && hasPermission(identity?.permissions, "REPORT") && <DialogPortal onClose={() => { setDialog(false); setSelectedFormForReport(null); setReportFormAnswers({}); setReportFormFiles({}); setReportFormReady(false); }} labelledBy="modal-title" className="modal report-modal"><div className="modal-heading"><div><div className="eyebrow">NEW OPERATIONAL RECORD</div><h2 id="modal-title">Log a defect</h2></div><button type="button" className="close" onClick={() => { setDialog(false); setSelectedFormForReport(null); setReportFormAnswers({}); setReportFormFiles({}); setReportFormReady(false); }} aria-label="Close">×</button></div><section className="report-system-context" aria-label="System-controlled report context"><span>Aircraft registration<b>{selectedAircraft?.tailNumber || "Unavailable"}</b></span><span>Cabin area<b>{areaMasterOptions.find(item => item.id === selectedAreaId)?.name || "Unavailable"}</b></span></section><form id="defect-report-form" onChange={() => { if(reportIdempotencyKey.current)reportIdempotencyKey.current=null; }} onSubmit={createDefect}>
      {selectedFormForReport && selectedAreaId ? <><p className="operational-form-preview-note">Complete the configured fields below. The selected published design controls the report fields and their order.</p><OperationalFormRenderer key={`${selectedAircraftId}:${selectedAreaId}:${selectedFormForReport.id}:${selectedFormForReport.versionId}`} request={operationalFormFetch} areaId={selectedAreaId} areaName={areaMasterOptions.find(item => item.id === selectedAreaId)?.name} areaCode={areaMasterOptions.find(item=>item.id===selectedAreaId)?.code} initialFormId={selectedFormForReport.id} initialVersionId={selectedFormForReport.versionId} preparationMode initialAnswers={reportFormAnswers} onAnswersChange={updateReportFormAnswers} onFilesChange={updateReportFormFiles} onPreparationReady={updateReportFormReady} /></> : <p role="alert">Choose an aircraft, cabin area, and published form before reporting.</p>}
      <div className="modal-note"><span>✓</span> Aircraft and organization identifiers are system-controlled. Answers and evidence are retained against the selected published version.</div><div className="modal-actions"><button type="button" className="button-secondary" onClick={() => { setDialog(false); setSelectedFormForReport(null); setReportFormAnswers({}); setReportFormReady(false); }}>Cancel</button><button className="button-primary" disabled={saving || !selectedFormForReport || !reportFormReady}>{saving ? "Saving…" : "Save defect →"}</button></div>
    </form></DialogPortal>}
    {selected && <DialogPortal onClose={() => setSelected(null)} labelledBy="defect-detail-title" className="modal detail-modal"><div className="modal-heading"><div><div className="eyebrow">{selected.reference} · {selected.tailNumber}</div><h2 id="defect-detail-title">{selected.title}</h2></div><button className="close" onClick={() => setSelected(null)} aria-label="Close">×</button></div><p className="detail-description">{selected.description}</p><div className="detail-grid"><span>Location<b>{human(selected.location)} · {selected.zone}</b></span><span>Condition<b>{selected.conditionName || "Not recorded"}</b></span><span>Severity<b>{selected.severityName || human(selected.severity)}</b></span><span>Status<b>{human(selected.status)}</b></span><span>Reported by<b>{selected.reportedBy}</b></span><span>Assigned to<b>{selected.assignedTo || "Unassigned"}</b></span></div>{formPurposeForStatus(selected.status,identity?.permissions) && <OperationalFormRenderer key={`${selected.id}:${formPurposeForStatus(selected.status,identity?.permissions)}:${initialFormForDefect?.formId || ""}:${initialFormForDefect?.versionId || ""}`} request={operationalFormFetch} defectId={selected.id} areaId={selected.areaId} areaName={areaMasterOptions.find(item => item.id === selected.areaId)?.name} areaCode={areaMasterOptions.find(item => item.id === selected.areaId)?.code} purpose={formPurposeForStatus(selected.status,identity?.permissions)!} initialFormId={initialFormForDefect?.defectId === selected.id ? initialFormForDefect.formId : undefined} initialVersionId={initialFormForDefect?.defectId === selected.id ? initialFormForDefect.versionId : undefined} initialAnswers={initialFormForDefect?.defectId === selected.id ? initialFormForDefect.answers : undefined} initialFiles={initialFormForDefect?.defectId===selected.id?initialFormForDefect.files:undefined}/>}<h3 className="timeline-heading">Audit history</h3><div className="timeline">{events.map((item) => <div className="timeline-item" key={item.id}><span className="timeline-dot"></span><div><b>{human(item.eventType)}{item.toStatus ? ` · ${human(item.toStatus)}` : ""}{item.inspectionOutcome ? ` · Inspection ${human(item.inspectionOutcome)}` : ""}</b><p>{item.note}{item.evidenceReference ? `\nEvidence: ${item.evidenceReference}` : ""}</p><small>{item.actorId} · {new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(new Date(item.occurredAt))}</small></div></div>)}{!events.length && <span className="timeline-empty">Loading history or no recorded events.</span>}</div><p className="modal-note"><span>✓</span> Form submissions and evidence are retained against the exact published version.</p><div className="modal-actions"><button className="button-secondary" onClick={() => setSelected(null)}>Close</button>{hasPermission(identity?.permissions, "REVIEW") && ["REPORTED","UNDER_REVIEW"].includes(selected.status) && <><button className="button-secondary" disabled={reviewSubmitting} onClick={() => void submitReviewDecision(selected,"REJECT")}>{reviewSubmitting?"Saving…":"Reject review"}</button><button className="button-primary" disabled={reviewSubmitting} onClick={() => void submitReviewDecision(selected,"APPROVE")}>{reviewSubmitting?"Saving…":"Approve review →"}</button></>}{hasPermission(identity?.permissions, "REVIEW") && <button className="button-secondary" onClick={() => void reassignReviewer(selected)}>Reassign Reviewer team</button>}{hasPermission(identity?.permissions, "ASSIGN_ACTION") && selected.activeActionId && ["ACTION_ASSIGNED", "IN_PROGRESS"].includes(selected.status) && <button className="button-secondary" onClick={() => void reassignDefect(selected)}>Reassign maintenance team</button>}{canAdvanceWorkflow(identity?.permissions, selected.status) && <button className="button-primary" onClick={() => { void advance(selected); setSelected(null); }}>Advance workflow →</button>}</div></DialogPortal>}
  </main>;
}
