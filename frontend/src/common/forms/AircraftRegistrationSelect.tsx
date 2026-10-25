"use client";

import { useInfiniteQuery } from "@tanstack/react-query";
import { useEffect, useId, useMemo, useRef, useState } from "react";
import { responseErrorMessage } from "@/common/alerts";
import {
  aircraftDropdownState,
  aircraftOptionLabel,
  aircraftOptionRequestPath,
  mergeAircraftPages,
  moveActiveAircraftIndex,
  nextAircraftPage,
  type AircraftOption,
  type AircraftOptionPage,
} from "./aircraftDropdownModel.mjs";

type Request = (path: string, init?: RequestInit) => Promise<Response>;

export default function AircraftRegistrationSelect({
  request,
  cacheKey,
  areaId,
  formId,
  id,
  label,
  helpText,
  required,
  selected,
  onSelect,
}: {
  request: Request;
  cacheKey: string;
  areaId: string;
  formId: string;
  id: string;
  label: string;
  helpText?: string | null;
  required: boolean;
  selected: AircraftOption | null;
  onSelect: (option: AircraftOption | null) => void;
}) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const [activeIndex, setActiveIndex] = useState(-1);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const optionRefs = useRef<Array<HTMLLIElement | null>>([]);
  const nextPageLoadingRef = useRef(false);
  const listboxId = useId();
  const statusId = useId();

  useEffect(() => {
    const timer = window.setTimeout(() => setDebouncedSearch(search.trim()), 250);
    return () => window.clearTimeout(timer);
  }, [search]);

  const query = useInfiniteQuery<AircraftOptionPage>({
    queryKey: ["reporting-aircraft-options", cacheKey, areaId, debouncedSearch],
    enabled: open,
    initialPageParam: 0,
    queryFn: async ({ pageParam, signal }) => {
      const response = await request(aircraftOptionRequestPath(debouncedSearch, Number(pageParam)), { signal });
      if (!response.ok) throw new Error(await responseErrorMessage(response, "Could not load active aircraft for this organization."));
      return response.json() as Promise<AircraftOptionPage>;
    },
    getNextPageParam: lastPage => nextAircraftPage(lastPage),
    staleTime: 30_000,
    retry: false,
  });

  const searchSettled = search.trim() === debouncedSearch;
  const options = useMemo(() => searchSettled ? mergeAircraftPages(query.data?.pages || []) : [], [query.data, searchSettled]);
  const state = aircraftDropdownState({ pending: query.isPending || !searchSettled, error: query.isError, options });
  const activeOption = activeIndex >= 0 ? options[activeIndex] : undefined;

  useEffect(() => {
    if (open) searchRef.current?.focus();
  }, [open]);

  useEffect(() => {
    if (!open) return;
    function closeOnOutsidePointer(event: PointerEvent) {
      if (event.target instanceof Node && !rootRef.current?.contains(event.target)) setOpen(false);
    }
    document.addEventListener("pointerdown", closeOnOutsidePointer);
    return () => document.removeEventListener("pointerdown", closeOnOutsidePointer);
  }, [open]);

  useEffect(() => {
    if (activeIndex >= 0) optionRefs.current[activeIndex]?.scrollIntoView({ block: "nearest" });
  }, [activeIndex]);

  function choose(option: AircraftOption) {
    onSelect(option);
    setOpen(false);
    setSearch("");
    setDebouncedSearch("");
    setActiveIndex(-1);
    triggerRef.current?.focus();
  }

  async function loadNextPage() {
    if (!query.hasNextPage || query.isFetchingNextPage || nextPageLoadingRef.current) return;
    nextPageLoadingRef.current = true;
    try {
      await query.fetchNextPage();
    } finally {
      nextPageLoadingRef.current = false;
    }
  }

  function handleKeyDown(event: React.KeyboardEvent<HTMLInputElement>) {
    if (event.key === "Escape") {
      event.preventDefault();
      setOpen(false);
      triggerRef.current?.focus();
      return;
    }
    if (event.key === "ArrowDown" || event.key === "ArrowUp" || event.key === "Home" || event.key === "End") {
      event.preventDefault();
      const direction = event.key === "ArrowUp" ? "previous" : event.key === "Home" ? "first" : event.key === "End" ? "last" : "next";
      setActiveIndex(index => moveActiveAircraftIndex(index, direction, options.length));
      if (options.length && query.hasNextPage && !query.isFetchingNextPage && (direction === "last" || (direction === "next" && activeIndex >= options.length - 2))) {
        void loadNextPage();
      }
      return;
    }
    if (event.key === "Enter" && activeOption) {
      event.preventDefault();
      choose(activeOption);
    }
  }

  function loadMoreIfNearEnd(event: React.UIEvent<HTMLUListElement>) {
    const list = event.currentTarget;
    if (query.hasNextPage && !query.isFetchingNextPage && list.scrollHeight - list.scrollTop - list.clientHeight < 64) {
      void loadNextPage();
    }
  }

  return <div ref={rootRef} className="relative grid min-w-0 content-start gap-[7px] self-start">
    <label className="grid gap-[6px]" htmlFor={`${id}-trigger`} onClick={event => { event.preventDefault(); setOpen(true); }}>
      <span className="dynamic-form-field-label">{label}{required && <span className="dynamic-form-required" aria-hidden="true">*</span>}</span>
      {helpText && <small>{helpText}</small>}
    </label>
    <button
      ref={triggerRef}
      id={`${id}-trigger`}
      type="button"
      className="flex h-[42px] min-h-[42px] w-full min-w-0 self-start items-center justify-between gap-3 rounded-[9px] border border-[#d7deea] bg-white px-[11px] py-[9px] text-left text-sm text-[#17212f] shadow-sm outline-none transition hover:border-[#718096] focus-visible:ring-2 focus-visible:ring-[#ff6b18] focus-visible:ring-offset-2"
      aria-haspopup="listbox"
      aria-expanded={open}
      aria-controls={open ? listboxId : undefined}
      aria-describedby={open ? statusId : undefined}
      onClick={() => setOpen(value => !value)}
    >
      <span className={selected ? "truncate" : "truncate text-[#64748b]"}>{selected ? aircraftOptionLabel(selected) : "Select an aircraft registration…"}</span>
      <span aria-hidden="true" className="shrink-0">{open ? "▴" : "▾"}</span>
    </button>

    {open && <div className="absolute left-0 right-0 top-full z-30 mt-1 overflow-hidden rounded-lg border border-[#cfd8e3] bg-white shadow-[0_12px_28px_#17212f24]" role="group" aria-label={`${label} options`}>
      <div className="border-b border-[#e5eaf0] p-2">
        <label className="sr-only" htmlFor={`${id}-search`}>Search aircraft registration or MSN</label>
        <input
          ref={searchRef}
          id={`${id}-search`}
          type="search"
          role="combobox"
          aria-autocomplete="list"
          aria-expanded="true"
          aria-controls={listboxId}
          aria-activedescendant={activeOption ? `${listboxId}-option-${activeOption.id}` : undefined}
          aria-describedby={statusId}
          value={search}
          onChange={event => { setSearch(event.target.value); setActiveIndex(-1); optionRefs.current = []; }}
          onKeyDown={handleKeyDown}
          placeholder="Search registration or MSN"
          className="w-full rounded-md border border-[#cfd8e3] bg-white px-3 py-2 text-sm text-[#17212f] outline-none placeholder:text-[#64748b] focus:border-[#ff6b18] focus:ring-2 focus:ring-[#ff6b1833]"
        />
      </div>
      <ul id={listboxId} role="listbox" aria-label="Aircraft registrations" aria-busy={query.isPending || query.isFetchingNextPage} onScroll={loadMoreIfNearEnd} className="m-0 max-h-60 list-none overflow-y-auto p-1">
        {options.map((option, index) => <li
          ref={element => { optionRefs.current[index] = element; }}
          id={`${listboxId}-option-${option.id}`}
          key={option.id}
          role="option"
          aria-selected={selected?.id === option.id}
          className={`cursor-pointer rounded-md px-3 py-2 text-sm outline-none ${activeIndex === index ? "bg-[#fff1e8] text-[#a63e00]" : "text-[#17212f] hover:bg-[#f3f6fa]"} ${selected?.id === option.id ? "font-semibold" : ""}`}
          onMouseEnter={() => setActiveIndex(index)}
          onMouseDown={event => event.preventDefault()}
          onClick={() => choose(option)}
        >{aircraftOptionLabel(option)}{option.aircraftType ? <small className="mt-0.5 block text-xs text-[#64748b]">{option.aircraftType}</small> : null}</li>)}
        {query.isFetchingNextPage && <li role="status" className="px-3 py-2 text-xs text-[#64748b]">Loading more aircraft…</li>}
      </ul>
      <div id={statusId} className="px-3 py-2 text-xs text-[#526173]" aria-live="polite">
        {state === "loading" && "Loading active aircraft…"}
        {state === "error" && <span role="alert">Aircraft could not be loaded. <button type="button" className="font-semibold underline underline-offset-2" onClick={() => void query.refetch()}>Retry</button></span>}
        {query.isError && options.length > 0 && <span role="alert">Aircraft could not be refreshed. Previously loaded options are still available. <button type="button" className="font-semibold underline underline-offset-2" onClick={() => void query.refetch()}>Retry</button></span>}
        {state === "empty" && "No active aircraft match this search."}
        {query.isFetchNextPageError && <span role="alert">More aircraft could not be loaded. <button type="button" className="font-semibold underline underline-offset-2" onClick={() => void loadNextPage()}>Retry</button></span>}
        {state === "ready" && !query.hasNextPage && `${query.data?.pages[0]?.totalElements ?? options.length} active aircraft`}
      </div>
    </div>}

    <select
      form={formId}
      required={required}
      value={selected?.id || ""}
      onChange={event => {
        if (!event.target.value) onSelect(null);
      }}
      onInvalid={event => {
        event.preventDefault();
        setOpen(true);
        triggerRef.current?.focus();
      }}
      aria-label={label}
      aria-hidden="true"
      tabIndex={-1}
      className="absolute h-px w-px overflow-hidden opacity-0"
    >
      <option value="">Select an aircraft registration</option>
      {selected && <option value={selected.id}>{aircraftOptionLabel(selected)}</option>}
    </select>
    {selected && <p className="selected-aircraft-context m-0 text-xs text-[#526173]" role="status">Selected aircraft: <strong>{selected.tailNumber}</strong> · MSN <strong>{selected.msnNumber || "—"}</strong> · {selected.aircraftType}</p>}
  </div>;
}
