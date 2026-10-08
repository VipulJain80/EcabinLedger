import type { SweetAlertOptions } from "sweetalert2";

export const alertConfig = {
  toast: {
    position: "top-end",
    timer: 3400,
    timerProgressBar: true,
    showConfirmButton: false,
    toast: true,
    customClass: { popup: "ecabin-alert-toast", title: "ecabin-alert-title" },
  } satisfies SweetAlertOptions,
  dialog: {
    buttonsStyling: false,
    reverseButtons: true,
    focusCancel: true,
    customClass: {
      popup: "ecabin-alert-dialog",
      title: "ecabin-alert-title",
      htmlContainer: "ecabin-alert-content",
      actions: "ecabin-alert-actions",
      confirmButton: "ecabin-alert-confirm",
      cancelButton: "ecabin-alert-cancel",
    },
  } satisfies SweetAlertOptions,
} as const;
