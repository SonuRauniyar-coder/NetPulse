import { Link } from "react-router-dom";
import { Compass, Hammer } from "lucide-react";
import { Button } from "@/components/ui/button";
import { EmptyState, PageHeader, Panel } from "@/components/common";

/** Honest placeholder: the page exists in navigation, but nothing is mocked. */
export function ComingSoon({ title, phase, summary }: { title: string; phase: 2 | 3; summary: string }) {
  return (
    <>
      <PageHeader title={title} />
      <Panel><EmptyState icon={Hammer} title={`Planned for Phase ${phase}`} description={summary} /></Panel>
    </>
  );
}

export function NotFound() {
  return (
    <div className="flex min-h-[60vh] items-center justify-center">
      <EmptyState icon={Compass} title="Page not found" description="This page doesn't exist or has moved."
        action={<Button asChild variant="outline" size="sm"><Link to="/">Back to dashboard</Link></Button>} />
    </div>
  );
}
