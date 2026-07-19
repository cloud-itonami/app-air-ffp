/**
 * air-ffp kotoba — barrel. kotoba-E2E split (ADR-2605181100): public program
 * catalog + de-identified aggregates plaintext; enrollee PII + per-member
 * ledger E2E. Fiat MoR settlement rail stays etzhayyim (consent-capability).
 */
export * from "./types.js";
export {
  registerTierBenefit,
  listTierBenefits,
  recordTierSummary,
  listTierSummary,
  enrollMember,
  listMembers,
  getMember,
  postLedgerEntry,
  listLedger,
  getLedgerEntry,
  coverage,
} from "./registry.js";
