"use client";

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { filterDropdownOptions, nextSelectedValues } from "./searchableDropdownModel.mjs";

export type SearchableOption = { value: string; label: string; description?: string };

type CommonProps = {
  id: string;
  label: string;
  options: SearchableOption[];
  disabled?: boolean;
  required?: boolean;
  form?: string;
  name?: string;
  placeholder?: string;
  helpText?: string | null;
  emptyMessage?: string;
  className?: string;
};

export function SearchableSelect({
  id, label, options, value, defaultValue = "", onChange, disabled = false, required = false, form, name, placeholder = "Choose…", helpText, emptyMessage = "No matching options.", className = "",
}: CommonProps & { value?: string; defaultValue?: string; onChange?: (value: string) => void }) {
  const [uncontrolledValue, setUncontrolledValue] = useState(defaultValue);
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const [activeIndex, setActiveIndex] = useState(-1);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const optionRefs = useRef<Array<HTMLLIElement | null>>([]);
  const generatedId = useId().replaceAll(":", "");
  const listboxId = `${generatedId}-listbox`;
  const labelId = `${generatedId}-label`;
  const statusId = `${generatedId}-status`;
  const filtered = useMemo(() => filterDropdownOptions(options, search), [options, search]);
  const activeOption = activeIndex >= 0 ? filtered[activeIndex] : undefined;
  const selectedValue = value ?? uncontrolledValue;
  const selected = options.find(option => option.value === selectedValue);

  useEffect(() => { if (open) searchRef.current?.focus(); }, [open]);
  const close = useCallback(() => { setOpen(false); setSearch(""); setActiveIndex(-1); }, []);
  useEffect(() => {
    if (!open) return;
    function closeOutside(event: PointerEvent) {
      if (event.target instanceof Node && !rootRef.current?.contains(event.target)) close();
    }
    document.addEventListener("pointerdown", closeOutside);
    return () => document.removeEventListener("pointerdown", closeOutside);
  }, [open, close]);
  useEffect(() => { if (activeIndex >= 0) optionRefs.current[activeIndex]?.scrollIntoView({ block: "nearest" }); }, [activeIndex]);

  function change(nextValue: string) { if (value === undefined) setUncontrolledValue(nextValue); onChange?.(nextValue); }
  function choose(option: SearchableOption) { change(option.value); close(); triggerRef.current?.focus(); }
  function onKeyDown(event: React.KeyboardEvent<HTMLInputElement>) {
    if (event.key === "Escape") { event.preventDefault(); close(); triggerRef.current?.focus(); return; }
    if (["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) {
      event.preventDefault();
      setActiveIndex(current => {
        if (!filtered.length) return -1;
        if (event.key === "Home") return 0;
        if (event.key === "End") return filtered.length - 1;
        if (event.key === "ArrowUp") return current <= 0 ? filtered.length - 1 : current - 1;
        return current < 0 || current >= filtered.length - 1 ? 0 : current + 1;
      });
    }
    if (event.key === "Enter" && activeOption) { event.preventDefault(); choose(activeOption); }
  }

  return <div ref={rootRef} className={`relative grid min-w-0 gap-[6px] ${className}`}>
    <label id={labelId} htmlFor={`${id}-trigger`} className="dynamic-form-field-label cursor-pointer" onClick={event => { event.preventDefault(); if (!disabled) setOpen(true); }}>{label}{required && <><span className="dynamic-form-required" aria-hidden="true">*</span><span className="sr-only">, required</span></>}</label>
    {helpText && <small>{helpText}</small>}
    <button ref={triggerRef} id={`${id}-trigger`} type="button" disabled={disabled} className="flex min-h-11 w-full min-w-0 items-center justify-between gap-3 rounded-lg border border-[#cfd8e3] bg-white px-3 py-2 text-left font-normal text-[#17212f] outline-none transition hover:border-[#8291a4] focus-visible:ring-2 focus-visible:ring-[#ff6b18] focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:bg-slate-50 disabled:text-slate-500" aria-labelledby={labelId} aria-haspopup="listbox" aria-expanded={open} aria-controls={open ? listboxId : undefined} onClick={() => setOpen(current => !current)}>
      <span className={selected ? "truncate" : "truncate text-[#64748b]"}>{selected?.label || selectedValue || placeholder}</span><span aria-hidden="true" className="shrink-0">{open ? "▴" : "▾"}</span>
    </button>
    {open && <div className="absolute left-0 right-0 top-full z-40 mt-1 overflow-hidden rounded-lg border border-[#cfd8e3] bg-white shadow-[0_12px_28px_#17212f24]" role="group" aria-label={`${label} options`}>
      <div className="border-b border-[#e5eaf0] p-2"><label className="sr-only" htmlFor={`${id}-search`}>Search {label.toLowerCase()}</label><input ref={searchRef} id={`${id}-search`} type="search" role="combobox" aria-autocomplete="list" aria-expanded="true" aria-controls={listboxId} aria-activedescendant={activeOption ? `${listboxId}-option-${activeIndex}` : undefined} aria-describedby={statusId} value={search} onChange={event => { setSearch(event.target.value); setActiveIndex(-1); optionRefs.current = []; }} onKeyDown={onKeyDown} placeholder={`Search ${label.toLowerCase()}`} className="w-full rounded-md border border-[#cfd8e3] bg-white px-3 py-2 text-sm outline-none placeholder:text-[#64748b] focus:border-[#ff6b18] focus:ring-2 focus:ring-[#ff6b1833]" /></div>
      {!required && selectedValue && <button type="button" className="w-full border-b border-[#e5eaf0] px-3 py-2 text-left text-sm text-[#526173] hover:bg-[#f3f6fa]" onClick={() => { change(""); close(); triggerRef.current?.focus(); }}>Clear selection</button>}
      <ul id={listboxId} role="listbox" aria-labelledby={labelId} className="m-0 max-h-60 list-none overflow-y-auto p-1">
        {filtered.map((option, index) => <li key={option.value} id={`${listboxId}-option-${index}`} ref={element => { optionRefs.current[index] = element; }} role="option" aria-selected={selectedValue === option.value} onMouseEnter={() => setActiveIndex(index)} onMouseDown={event => event.preventDefault()} onClick={() => choose(option)} className={`cursor-pointer rounded-md px-3 py-2 text-sm outline-none ${activeIndex === index ? "bg-[#fff1e8] text-[#a63e00]" : "text-[#17212f] hover:bg-[#f3f6fa]"} ${selectedValue === option.value ? "font-semibold" : ""}`}>
          {option.label}{option.description && <small className="mt-0.5 block text-xs text-[#64748b]">{option.description}</small>}
        </li>)}
      </ul>
      <p id={statusId} className="m-0 border-t border-[#e5eaf0] px-3 py-2 text-xs text-[#526173]" aria-live="polite">{filtered.length ? `${filtered.length} options` : emptyMessage}</p>
    </div>}
    <select id={id} name={name} form={form} required={required} disabled={disabled} value={selectedValue} onChange={event => change(event.target.value)} aria-label={label} aria-hidden="true" tabIndex={-1} onInvalid={event => { event.preventDefault(); setOpen(true); }} className="absolute h-px w-px overflow-hidden opacity-0">
      <option value="">{placeholder}</option>{selectedValue && !selected && <option value={selectedValue}>{selectedValue}</option>}{options.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
    </select>
  </div>;
}

export function SearchableMultiSelect({
  id, label, options, value, onChange, disabled = false, required = false, form, placeholder = "Choose options…", helpText, emptyMessage = "No matching options.", className = "",
}: CommonProps & { value: string[]; onChange: (value: string[]) => void }) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const generatedId = useId().replaceAll(":", "");
  const labelId = `${generatedId}-label`;
  const filtered = useMemo(() => filterDropdownOptions(options, search), [options, search]);
  const selectedLabels = value.map(selected => options.find(option => option.value === selected)?.label || selected);

  useEffect(() => { if (open) searchRef.current?.focus(); }, [open]);
  const close = useCallback(() => { setOpen(false); setSearch(""); }, []);
  useEffect(() => {
    if (!open) return;
    function closeOutside(event: PointerEvent) { if (event.target instanceof Node && !rootRef.current?.contains(event.target)) close(); }
    document.addEventListener("pointerdown", closeOutside);
    return () => document.removeEventListener("pointerdown", closeOutside);
  }, [open, close]);

  return <div ref={rootRef} className={`relative grid min-w-0 gap-[6px] ${className}`}>
    <label id={labelId} htmlFor={`${id}-trigger`} className="dynamic-form-field-label cursor-pointer" onClick={event => { event.preventDefault(); if (!disabled) setOpen(true); }}>{label}{required && <><span className="dynamic-form-required" aria-hidden="true">*</span><span className="sr-only">, required</span></>}</label>
    {helpText && <small>{helpText}</small>}
    <button ref={triggerRef} id={`${id}-trigger`} type="button" disabled={disabled} className="flex min-h-11 w-full min-w-0 items-center justify-between gap-3 rounded-lg border border-[#cfd8e3] bg-white px-3 py-2 text-left font-normal text-[#17212f] outline-none transition hover:border-[#8291a4] focus-visible:ring-2 focus-visible:ring-[#ff6b18] focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:bg-slate-50 disabled:text-slate-500" aria-labelledby={labelId} aria-haspopup="dialog" aria-expanded={open} aria-controls={open ? `${generatedId}-dialog` : undefined} onClick={() => setOpen(current => !current)}>
      <span className={selectedLabels.length ? "truncate" : "truncate text-[#64748b]"}>{selectedLabels.length ? selectedLabels.join(", ") : placeholder}</span><span aria-hidden="true" className="shrink-0">{open ? "▴" : "▾"}</span>
    </button>
    {open && <div id={`${generatedId}-dialog`} className="absolute left-0 right-0 top-full z-40 mt-1 overflow-hidden rounded-lg border border-[#cfd8e3] bg-white shadow-[0_12px_28px_#17212f24]" role="dialog" aria-label={`${label} options`} onKeyDown={event => { if (event.key === "Escape") { event.preventDefault(); close(); triggerRef.current?.focus(); } }}>
      <div className="border-b border-[#e5eaf0] p-2"><label className="sr-only" htmlFor={`${id}-search`}>Search {label.toLowerCase()}</label><input ref={searchRef} id={`${id}-search`} type="search" value={search} onChange={event => setSearch(event.target.value)} onKeyDown={event => { if (event.key === "Escape") { event.preventDefault(); close(); triggerRef.current?.focus(); } }} placeholder={`Search ${label.toLowerCase()}`} className="w-full rounded-md border border-[#cfd8e3] bg-white px-3 py-2 text-sm outline-none placeholder:text-[#64748b] focus:border-[#ff6b18] focus:ring-2 focus:ring-[#ff6b1833]" /></div>
      <div className="max-h-60 overflow-y-auto p-1" role="group" aria-label={`${label} choices`}>
        {filtered.map(option => <label key={option.value} className="flex cursor-pointer items-start gap-2 rounded-md px-3 py-2 text-sm text-[#17212f] hover:bg-[#f3f6fa]"><input type="checkbox" checked={value.includes(option.value)} disabled={disabled} onChange={() => onChange(nextSelectedValues(value, option.value))} className="mt-0.5"/><span className="min-w-0">{option.label}{option.description && <small className="block text-xs text-[#64748b]">{option.description}</small>}</span></label>)}
      </div>
      <div className="flex items-center justify-between gap-2 border-t border-[#e5eaf0] px-3 py-2 text-xs text-[#526173]"><span aria-live="polite">{filtered.length ? `${filtered.length} options` : emptyMessage}</span><div className="flex items-center gap-3">{value.length>0&&<button type="button" className="font-semibold underline underline-offset-2" onClick={()=>onChange([])}>Clear all</button>}<button type="button" className="font-semibold underline underline-offset-2" onClick={()=>{close();triggerRef.current?.focus();}}>Done</button></div></div>
    </div>}
    <select id={id} form={form} multiple required={required} value={value} disabled={disabled} aria-label={label} aria-hidden="true" tabIndex={-1} className="absolute h-px w-px overflow-hidden opacity-0" onInvalid={event => { event.preventDefault(); setOpen(true); }} onChange={event => onChange(Array.from(event.target.selectedOptions, option => option.value))}>
      {options.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}{value.filter(selected => !options.some(option => option.value === selected)).map(selected => <option key={selected} value={selected}>{selected}</option>)}
    </select>
  </div>;
}
