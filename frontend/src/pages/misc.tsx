import { Link } from "react-router-dom";
import { Compass } from "lucide-react";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/common";

export function NotFound() {
  return (
    <div className="flex min-h-[60vh] items-center justify-center">
      <EmptyState icon={Compass} title="Page not found" description="This page doesn't exist or has moved."
        action={<Button asChild variant="outline" size="sm"><Link to="/">Back to dashboard</Link></Button>} />
    </div>
  );
}
