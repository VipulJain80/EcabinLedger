"use client";

import { useMemo, useState } from "react";

export type TeamMembershipOption = {
  id: string;
  name: string;
  teamType: "MAINTENANCE" | "REVIEWER" | string;
  isactive: number;
};

type Props = {
  options: TeamMembershipOption[];
  initialIds?: string;
  loading: boolean;
  error?: string;
  disabled?: boolean;
  onRetry: () => void;
};

export default function TeamMembershipSelect({ options, initialIds = "", loading, error, disabled = false, onRetry }: Props) {
  const [selectedIds, setSelectedIds] = useState(() => initialIds.split("|").filter(Boolean));
  const [draftIds, setDraftIds] = useState<string[]>([]);
  const [search, setSearch] = useState("");
  const [open, setOpen] = useState(false);
  const selected = useMemo(() => options.filter((team) => selectedIds.includes(team.id)), [options, selectedIds]);
  const filtered = useMemo(() => options.filter((team) => team.name.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase())), [options, search]);

  function closeWithoutApplying() {
    setDraftIds(selectedIds);
    setOpen(false);
  }

  return <div className="team-membership-select">
    <input type="hidden" name="teamIds" value={selectedIds.join("|")} />
    <button type="button" className="team-membership-trigger" aria-expanded={open} aria-haspopup="dialog" aria-controls="team-membership-options" disabled={disabled || loading} onClick={() => { if (!open) { setDraftIds(selectedIds); setSearch(""); } setOpen((value) => !value); }}>
      <span>{loading ? "Loading teams…" : selected.length ? `${selected.length} team${selected.length === 1 ? "" : "s"} selected` : "Select teams…"}</span><span aria-hidden="true">⌄</span>
    </button>
    {selected.length > 0 && <div className="team-membership-chips" aria-label="Selected teams">{selected.map((team) => <span className="team-membership-chip" key={team.id}>{team.name}<button type="button" aria-label={`Remove ${team.name}`} title={`Remove ${team.name}`} disabled={disabled} onClick={() => { setSelectedIds((current) => current.filter((id) => id !== team.id)); setDraftIds((current) => current.filter((id) => id !== team.id)); }}>×</button></span>)}</div>}
    {open && <section id="team-membership-options" className="team-membership-popover" role="dialog" aria-label="Select team memberships">
      <label className="team-membership-search">Search teams<input type="search" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Search teams…" autoComplete="off" /></label>
      {error ? <div className="team-membership-message" role="alert">{error}<button className="button-secondary" type="button" onClick={onRetry}>Retry</button></div> : loading ? <p className="team-membership-message" role="status">Loading active organization teams…</p> : !options.length ? <p className="team-membership-message">No active teams are available.</p> : !filtered.length ? <p className="team-membership-message">No teams match your search.</p> : <div className="team-membership-options">{filtered.map((team) => <label className="team-membership-option" key={team.id}><input type="checkbox" checked={draftIds.includes(team.id)} onChange={(event) => setDraftIds((current) => event.target.checked ? [...current, team.id] : current.filter((id) => id !== team.id))} /><span>{team.name}<small>{team.teamType === "REVIEWER" ? "Reviewer team" : team.teamType === "CLOSURE" ? "Closure team" : "Maintenance team"}</small></span></label>)}</div>}
      <div className="team-membership-footer"><span>{draftIds.length} selected</span><div><button type="button" className="button-secondary" onClick={closeWithoutApplying}>Cancel</button><button type="button" className="button-primary" onClick={() => { setSelectedIds(draftIds); setOpen(false); }} disabled={Boolean(error) || loading}>Apply</button></div></div>
    </section>}
  </div>;
}
