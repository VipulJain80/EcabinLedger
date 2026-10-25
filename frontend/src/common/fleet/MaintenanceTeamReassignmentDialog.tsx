"use client";

import { type FormEvent, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import DialogPortal from "@/common/dialogs/DialogPortal";
import { alert, responseErrorMessage } from "@/common/alerts";
import { SearchableSelect } from "@/common/selects/SearchableSelect";
import { shouldUseSearchableSelect } from "@/common/selects/searchableDropdownModel.mjs";

type MaintenanceTeam = { id: string; name: string };
type MaintenanceTeamMember = { externalUserId: string; displayName: string };

export default function MaintenanceTeamReassignmentDialog({ defectId, actionId, currentTeam, request, onClose, onComplete }: {
  defectId: string;
  actionId: string;
  currentTeam: string | null;
  request: (path: string, init?: RequestInit) => Promise<Response>;
  onClose: () => void;
  onComplete: () => Promise<void>;
}) {
  const [teamId, setTeamId] = useState("");
  const [assignedUserId, setAssignedUserId] = useState("");
  const [reason, setReason] = useState("");
  const [saving, setSaving] = useState(false);
  const [submitError, setSubmitError] = useState("");
  const options = useQuery<MaintenanceTeam[]>({
    queryKey: ["maintenance-team-reassignment-options", defectId, actionId],
    queryFn: async ({ signal }) => {
      const response = await request(`/defects/${encodeURIComponent(defectId)}/actions/${encodeURIComponent(actionId)}/reassignment-options`, { signal });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load active maintenance teams."));
      return response.json();
    },
    retry: false,
  });
  const members = useQuery<MaintenanceTeamMember[]>({
    queryKey: ["maintenance-team-reassignment-members", defectId, actionId, teamId],
    enabled: Boolean(teamId),
    queryFn: async ({ signal }) => {
      const response = await request(`/defects/${encodeURIComponent(defectId)}/actions/${encodeURIComponent(actionId)}/reassignment-options/${encodeURIComponent(teamId)}/members`, { signal });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load active members of this team."));
      return response.json();
    },
    retry: false,
  });
  const availableTeams = (options.data || []).filter(team => team.name !== currentTeam);
  const selectedTeam = availableTeams.find(team => team.id === teamId);
  const selectedMember = members.data?.find(member => member.externalUserId === assignedUserId);
  const duplicateDisplayNames = new Set((members.data || []).map(member => member.displayName)).size !== (members.data || []).length;
  const searchableTeams = shouldUseSearchableSelect(availableTeams.length, 200) && (options.data?.length ?? 0) < 200;
  const searchableMembers = shouldUseSearchableSelect(members.data?.length ?? 0, 200);
  const canSubmit = Boolean(selectedTeam && reason.trim() && !saving && !options.isPending && !options.isError);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canSubmit || !selectedTeam) return;
    setSaving(true);
    setSubmitError("");
    try {
      const response = await request(`/defects/${encodeURIComponent(defectId)}/actions/${encodeURIComponent(actionId)}/reassign`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ assignedTeamId: selectedTeam.id, assignedUserId: assignedUserId.trim() || null, reason: reason.trim() }),
      });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not reassign this corrective action."));
      await onComplete();
      await alert.success("Maintenance team reassigned.");
      onClose();
    } catch (error) {
      const message = error instanceof Error ? error.message : "Could not reassign this corrective action.";
      setSubmitError(message);
      await alert.error(message);
    } finally {
      setSaving(false);
    }
  }

  return <DialogPortal onClose={() => { if (!saving) onClose(); }} labelledBy="maintenance-team-reassignment-title" className="w-full max-w-xl rounded-2xl border border-[#dce5df] bg-white p-5 shadow-2xl sm:p-6" closeOnEscape={!saving}>
    <header className="mb-5 flex items-start justify-between gap-4">
      <div>
        <h2 id="maintenance-team-reassignment-title" className="mb-0 mt-1 text-xl font-bold">Reassign Maintenance Team</h2>
        <p className="mb-0 mt-2 text-sm text-[#000000]">Current assignment: <strong>{currentTeam || "Unassigned"}</strong></p>
      </div>
      <button type="button" aria-label="Close reassignment dialog" disabled={saving} onClick={onClose} className="grid h-9 w-9 shrink-0 place-items-center rounded-lg border border-[#dce5df] text-lg disabled:opacity-50">×</button>
    </header>
    <form onSubmit={submit} className="grid min-w-0 gap-4">
      {searchableTeams
        ? <SearchableSelect id="reassignment-team" label="Select a maintenance team" options={availableTeams.map(team => ({ value: team.id, label: team.name }))} placeholder="Select an active maintenance team" required value={teamId} disabled={options.isPending || options.isError || availableTeams.length === 0 || saving} onChange={value => { setTeamId(value); setAssignedUserId(""); }} className="text-sm font-semibold" />
        : <label className="grid gap-1.5 text-sm font-semibold" htmlFor="reassignment-team">Select a maintenance team
          <select id="reassignment-team" required value={teamId} onChange={event => { setTeamId(event.target.value); setAssignedUserId(""); }} disabled={options.isPending || options.isError || availableTeams.length === 0 || saving} className="min-h-11 min-w-0 rounded-lg border border-[#cfdad2] bg-white px-3 py-2 font-normal focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#3165bd]">
            <option value="">Select an active maintenance team</option>
            {availableTeams.map(team => <option key={team.id} value={team.id}>{team.name}</option>)}
          </select>
        </label>}
      {options.isPending && <p className="m-0 text-sm text-[#000000]" role="status">Loading active maintenance teams…</p>}
      {options.isError && <div className="grid gap-2" role="alert"><p className="m-0 text-sm text-[#9a4035]">{options.error.message}</p><button type="button" className="button-secondary w-fit" onClick={() => void options.refetch()}>Retry loading teams</button></div>}
      {!options.isPending && !options.isError && availableTeams.length === 0 && <p className="m-0 rounded-lg bg-[#f7faf8] p-3 text-sm text-[#000000]" role="status">No other active maintenance teams are available.</p>}
      {searchableMembers
        ? <SearchableSelect id="reassignment-assignee" label="Optional individual assignee" options={(members.data || []).map(member => ({ value: member.externalUserId, label: `${member.displayName}${duplicateDisplayNames ? ` · ${member.externalUserId}` : ""}` }))} placeholder="Leave unassigned — active team members can take the work" value={assignedUserId} disabled={!teamId || members.isPending || members.isError || saving} onChange={setAssignedUserId} className="text-sm font-semibold" />
        : <label className="grid gap-1.5 text-sm font-semibold" htmlFor="reassignment-assignee">Optional individual assignee
          <select id="reassignment-assignee" value={assignedUserId} onChange={event => setAssignedUserId(event.target.value)} disabled={!teamId || members.isPending || members.isError || (members.data?.length ?? 0) === 0 || saving} className="min-h-11 min-w-0 rounded-lg border border-[#cfdad2] bg-white px-3 py-2 font-normal focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#3165bd]">
            <option value="">Leave unassigned — active team members can take the work</option>
            {(members.data || []).map(member => <option key={member.externalUserId} value={member.externalUserId}>{member.displayName}{duplicateDisplayNames ? ` · ${member.externalUserId}` : ""}</option>)}
          </select>
          <span className="text-xs font-normal text-[#000000]">Choose a person from the selected team, or leave this empty for the team to take the work.</span>
        </label>}
      {teamId && members.isPending && <p className="m-0 text-sm text-[#000000]" role="status">Loading active team members…</p>}
      {teamId && members.isError && <div className="grid gap-2" role="alert"><p className="m-0 text-sm text-[#9a4035]">{members.error.message}</p><button type="button" className="button-secondary w-fit" onClick={() => void members.refetch()}>Retry loading team members</button></div>}
      {teamId && !members.isPending && !members.isError && members.data?.length === 0 && <p className="m-0 text-sm text-[#000000]" role="status">No active members with maintenance-work access are assigned to this team. You can leave the action unassigned to a person.</p>}
      {assignedUserId && !selectedMember && !members.isPending && !members.isError && <p className="m-0 text-sm text-[#9a4035]" role="alert">Choose an active member from the selected team or leave the assignment empty.</p>}
      <label className="grid gap-1.5 text-sm font-semibold" htmlFor="reassignment-reason">Reassignment reason
        <textarea id="reassignment-reason" required maxLength={2000} rows={3} value={reason} onChange={event => setReason(event.target.value)} disabled={saving} className="min-w-0 resize-y rounded-lg border border-[#cfdad2] px-3 py-2 font-normal focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#3165bd]" />
      </label>
      {submitError && <p className="m-0 text-sm text-[#9a4035]" role="alert">{submitError}</p>}
      <footer className="flex flex-wrap justify-end gap-2 border-t border-[#edf1ed] pt-4">
        <button type="button" className="button-secondary" disabled={saving} onClick={onClose}>Cancel</button>
        <button type="submit" className="button-primary" disabled={!canSubmit}>{saving ? "Reassigning…" : "Reassign"}</button>
      </footer>
    </form>
  </DialogPortal>;
}

export function ReassignTeamButton({ onClick }: { onClick: () => void }) {
  return <button type="button" onClick={onClick} className="inline-flex min-h-10 shrink-0 items-center justify-center gap-2 rounded-lg border border-[#cbd8ce] bg-white px-3.5 py-2 text-sm font-semibold text-[#315b43] shadow-sm transition-colors hover:border-[#9db2a2] hover:bg-[#f3f7f4] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#3165bd]">
    <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" className="h-4 w-4"><path d="m16 3 4 4-4 4"/><path d="M20 7H4"/><path d="m8 21-4-4 4-4"/><path d="M4 17h16"/></svg>
    <span>Reassign Team</span>
  </button>;
}
