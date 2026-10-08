import Swal, { type SweetAlertIcon, type SweetAlertOptions } from "sweetalert2";
import { alertConfig } from "./alertConfig";

function toast(icon: SweetAlertIcon, title: string) {
  return Swal.fire({ ...alertConfig.toast, icon, title });
}

export const alert = {
  success: (message: string) => toast("success", message),
  error: (message: string) => toast("error", message),
  warning: (message: string) => toast("warning", message),
  info: (message: string) => toast("info", message),
  confirm: async (options: { title: string; text?: string; confirmText?: string; cancelText?: string; destructive?: boolean }) => {
    const result = await Swal.fire({
      ...alertConfig.dialog,
      icon: options.destructive ? "warning" : "question",
      title: options.title,
      text: options.text,
      showCancelButton: true,
      confirmButtonText: options.confirmText || "Continue",
      cancelButtonText: options.cancelText || "Cancel",
      customClass: {
        ...alertConfig.dialog.customClass,
        confirmButton: options.destructive ? "ecabin-alert-confirm ecabin-alert-danger" : "ecabin-alert-confirm",
      },
    });
    return result.isConfirmed;
  },
  prompt: async (options: { title: string; text?: string; inputLabel?: string; placeholder?: string; initialValue?: string; required?: boolean }) => {
    const result = await Swal.fire<string>({
      ...alertConfig.dialog,
      title: options.title,
      text: options.text,
      input: "text",
      inputLabel: options.inputLabel,
      inputPlaceholder: options.placeholder,
      inputValue: options.initialValue || "",
      showCancelButton: true,
      confirmButtonText: "Continue",
      cancelButtonText: "Cancel",
      inputValidator: options.required === false ? undefined : (value) => value.trim() ? undefined : "This field is required.",
    } satisfies SweetAlertOptions);
    return result.isConfirmed ? result.value?.trim() || "" : null;
  },
  loading: (message: string) => {
    void Swal.fire({ ...alertConfig.dialog, title: message, allowOutsideClick: false, allowEscapeKey: false, didOpen: () => Swal.showLoading() });
  },
  close: () => Swal.close(),
};

export function getApiErrorMessage(error: unknown, fallback = "Something went wrong. Please try again.") {
  if (error instanceof Response) return responseErrorMessage(error, fallback);
  if (error instanceof Error && error.message.trim()) return error.message;
  if (typeof error === "string" && error.trim()) return error;
  return fallback;
}

export async function responseErrorMessage(response: Response, fallback = "The request could not be completed.") {
  const body = await response.clone().json().catch(() => null) as { error?: { message?: string }; message?: string; detail?: string; errors?: Array<{ defaultMessage?: string; message?: string }> } | null;
  return body?.error?.message || body?.message || body?.detail || body?.errors?.map((item) => item.defaultMessage || item.message).filter(Boolean).join(" ") || fallback;
}
