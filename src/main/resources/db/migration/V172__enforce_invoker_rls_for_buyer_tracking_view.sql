-- A view owned by a BYPASSRLS role would otherwise read through forced RLS
-- using that owner's privileges. Use the caller's privileges and RLS context.
ALTER VIEW logistics.buyer_delivery_tracking
    SET (security_invoker = true);
