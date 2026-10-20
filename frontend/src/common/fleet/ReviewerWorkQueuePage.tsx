"use client";

import { useEffect, useState } from "react";
import { ColumnDef, SortingState } from "@tanstack/react-table";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import DataTable, { ServerTablePage } from "@/common/tables/DataTable";
import { responseErrorMessage } from "@/common/alerts";
import { formatDateTime } from "@/common/dateTime";
import { useDefectWorkflowStatuses, useDefectSeverities, workflowStatusLabel, WorkflowStatusBadge, SeverityBadge } from "@/common/workflow/workflowStatuses";

type ReviewItem = { id:string; defectId:string; aircraftRegistration:string; msnNumber:string|null; aircraftType:string; zone:string; component:string|null; severity:string; severityName?:string; status:string; reviewerTeam:string|null; maintenanceTeam:string|null; routingNeedsAttention:boolean; reportedAt:string };
type Request=(path:string,init?:RequestInit)=>Promise<Response>;
const REVIEW_QUEUE_FILTER_CODES = ["REPORTED","UNDER_REVIEW","INSPECTION_REQUIRED","IN_PROGRESS","AWAITING_VERIFICATION","VERIFIED","APPROVAL_REQUIRED","REOPENED"];
const date=formatDateTime;

export default function ReviewerWorkQueuePage({request,onOpen}:{request:Request;onOpen:(defectId:string)=>void}){
  const workflowStatuses = useDefectWorkflowStatuses(request).data?.items || [];
  const severityDefinitions = useDefectSeverities(request).data || [];
  const [page,setPage]=useState(0),[pageSize,setPageSize]=useState(25),[search,setSearch]=useState(""),[status,setStatus]=useState("ALL"),[sorting,setSorting]=useState<SortingState>([{id:"reportedAt",desc:true}]);
  const queryClient=useQueryClient();
  const query=useQuery<ServerTablePage<ReviewItem>>({queryKey:["review-queue",page,pageSize,search,status,sorting],queryFn:async({signal})=>{const params=new URLSearchParams({page:String(page),size:String(pageSize),status});if(search.trim())params.set("search",search.trim());if(sorting[0]){params.set("sortBy",sorting[0].id);params.set("sortDirection",sorting[0].desc?"DESC":"ASC");}const response=await request(`/review-queue?${params}`,{signal});if(!response.ok)throw new Error(await responseErrorMessage(response,"Could not load the shared Reviewer queue."));return response.json() as Promise<ServerTablePage<ReviewItem>>;}});
  useEffect(()=>{const refresh=()=>void queryClient.invalidateQueries({queryKey:["review-queue"]});window.addEventListener("review-queue-refresh",refresh);return()=>window.removeEventListener("review-queue-refresh",refresh);},[queryClient]);
  const columns:ColumnDef<ReviewItem,unknown>[]=[
    {accessorKey:"defectId",header:"Defect ID",enableSorting:true,cell:({row})=><button className="work-queue-open" type="button" onClick={()=>onOpen(row.original.id)}>{row.original.defectId}</button>},
    {accessorKey:"aircraftRegistration",header:"Aircraft"},{accessorKey:"msnNumber",header:"MSN",cell:({getValue})=>String(getValue()||"—")},{accessorKey:"aircraftType",header:"Aircraft type"},{accessorKey:"zone",header:"Cabin zone"},{accessorKey:"component",header:"Component",cell:({getValue})=>String(getValue()||"—")},{accessorKey:"severity",header:"Severity",cell:({row})=><SeverityBadge request={request} definitions={severityDefinitions} severityCode={row.original.severity} severityName={row.original.severityName}/>},{accessorKey:"status",header:"Status",cell:({getValue})=><WorkflowStatusBadge statusCode={String(getValue())} statuses={workflowStatuses}/>},{accessorKey:"reviewerTeam",header:"Reviewer team",cell:({row})=>row.original.routingNeedsAttention?<strong className="routing-attention">Needs eligible Reviewer</strong>:String(row.original.reviewerTeam||"Unassigned · configure route")},{accessorKey:"maintenanceTeam",header:"Maintenance team",cell:({getValue})=>String(getValue()||"—")},{accessorKey:"reportedAt",header:"Reported",cell:({getValue})=>date(String(getValue()))},
  ];
  return <div className="content maintenance-work-queue"><div className="heading"><div><h1>Reviewer queue</h1></div></div><section className="configuration-table-card"><div className="configuration-table-head flex min-h-[82px] min-w-0 items-center justify-between gap-3 px-6 py-[18px] max-[700px]:min-h-[70px] max-[700px]:items-start max-[700px]:flex-col max-[700px]:p-4"><div><div className="eyebrow">TEAM ROUTED DEFECTS</div><h2>Review assignments</h2></div></div><DataTable<ReviewItem> columns={columns} data={query.data?.items||[]} total={query.data?.totalElements||0} page={page} pageSize={pageSize} sorting={sorting} search={search} loading={query.isPending||query.isFetching} error={query.isError} onPageChange={setPage} onPageSizeChange={size=>{setPageSize(size);setPage(0);}} onSortingChange={next=>{setSorting(next);setPage(0);}} onSearchChange={value=>{setSearch(value);setPage(0);}} onRetry={()=>void query.refetch()} onExport={()=>undefined} exporting={false} exportable={false} selectable={false} filters={<label className="admin-inline-filter">Review status<select value={status} onChange={e=>{setStatus(e.target.value);setPage(0);}}><option value="ALL">Pending and routed work</option>{REVIEW_QUEUE_FILTER_CODES.map(code=>{const item=workflowStatuses.find(value=>value.statusCode===code&&value.isActive);return item?<option key={code} value={code}>{workflowStatusLabel(code, workflowStatuses)}</option>:null;})}</select></label>}/></section></div>;
}
