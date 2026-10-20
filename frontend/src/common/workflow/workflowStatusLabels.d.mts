export type WorkflowStatusLabelRecord = {
  statusCode: string;
  aliasName?: string | null;
  displayName?: string | null;
};

export function workflowStatusLabel(code: string | null | undefined, items?: readonly WorkflowStatusLabelRecord[]): string;
