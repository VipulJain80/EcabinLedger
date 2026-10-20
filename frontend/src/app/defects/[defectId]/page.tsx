import { Suspense } from "react";
import DefectDetailsPage from "@/common/fleet/DefectDetailsPage";

export default async function DefectDetailsRoute({ params }: { params: Promise<{ defectId: string }> }) {
  const { defectId } = await params;
  return <Suspense fallback={<main className="min-h-screen bg-[#eaf1ff] p-6"><div className="mx-auto max-w-6xl rounded-2xl bg-white p-8">Loading defect details…</div></main>}><DefectDetailsPage defectId={defectId} /></Suspense>;
}
