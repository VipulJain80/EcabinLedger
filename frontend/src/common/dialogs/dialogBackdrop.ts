type BackdropEvent = {
  target: EventTarget | null;
  currentTarget: EventTarget | null;
  preventDefault(): void;
  stopPropagation(): void;
};

/** Consume backdrop presses without dismissing the dialog or clicking through to the page. */
export function handleDialogBackdropMouseDown(event: BackdropEvent): void {
  if (event.target !== event.currentTarget) return;
  event.preventDefault();
  event.stopPropagation();
}
