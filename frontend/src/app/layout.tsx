import type { Metadata } from "next";
import "./globals.css";
import "./details.css";
import "./login.css";
import "./configuration.css";
import "sweetalert2/dist/sweetalert2.min.css";
import QueryProvider from "@/common/query/QueryProvider";

export const metadata: Metadata = {
  title: "Defect Summary | eCabin Ledger",
  description: "Track cabin defects from inspection through closure with an audit-ready operational record.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="en"><body><QueryProvider>{children}</QueryProvider></body></html>;
}
