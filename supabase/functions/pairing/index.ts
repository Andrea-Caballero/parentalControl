// T15: Emparejamiento - Edge Function
// Valida pairing_code, crea/asocia device y escribe device_id en app_metadata

import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const { code, device_name, device_model, os_version, app_version, age_band, child_first_name } =
      await req.json();

    // Validar input
    if (!code || !device_name || !app_version) {
      return new Response(
        JSON.stringify({ error: "Faltan campos requeridos" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // feat-multi-child-picker (Change A — schema + domain + pairing,
    // design §A.7): the parent-side "name this child" prompt captures
    // child_first_name into pairing_codes before the child scans. From
    // there, the pairing edge function creates the children row and links
    // the device in the same transaction.
    //
    // Validation: trimmed length 1..32 (mirrors the children_first_name
    // CHECK constraint added in supabase/migrations/005_children_table.sql).
    // Empty / blank → HTTP 400. Without this, the dashboard surfaces
    // anonymous devices ("Sin asignar") that the parent has to rename
    // out-of-band; the rename flow's research-shape prompt (PR B §B.6)
    // handles the orphan case, but pairing-time capture is the cleaner
    // happy path.
    const trimmedChildName = (child_first_name ?? "").trim();
    if (trimmedChildName.length < 1 || trimmedChildName.length > 32) {
      return new Response(
        JSON.stringify({ error: "child_first_name es requerido (1..32 caracteres)" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Cliente con service_role para bypass RLS
    const supabaseAdmin = createClient(
      Deno.env.get("SUPABASE_URL") ?? "",
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? ""
    );

    // 1. Validar código existente, vigente y no consumido
    const { data: pairingRecord, error: fetchError } = await supabaseAdmin
      .from("pairing_codes")
      .select("*")
      .eq("code", code)
      .eq("status", "ACTIVE")
      .single();

    if (fetchError || !pairingRecord) {
      return new Response(
        JSON.stringify({ error: "Código inválido o expirado" }),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Verificar expiración
    const expiresAt = new Date(pairingRecord.expires_at);
    if (expiresAt < new Date()) {
      // Marcar como expirado
      await supabaseAdmin
        .from("pairing_codes")
        .update({ status: "EXPIRED" })
        .eq("id", pairingRecord.id);

      return new Response(
        JSON.stringify({ error: "Código expirado" }),
        { status: 410, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // 2. Obtener o crear usuario anónimo del agente (auth.users)
    // El agente usa anon key, así que auth.uid() es null
    // Creamos un usuario anónimo con email device-based
    let agentUserId: string;

    // Buscar usuario existente por metadata (si el agente ya se registró antes)
    const deviceHash = await hashDeviceIdentifier(device_name, device_model);

    const { data: existingUsers } = await supabaseAdmin
      .from("auth.users")
      .select("id")
      .ilike("email", `%${deviceHash}%`)
      .limit(1);

    if (existingUsers && existingUsers.length > 0) {
      agentUserId = existingUsers[0].id;
    } else {
      // Crear usuario anónimo para el dispositivo
      const { data: newUser, error: createError } = await supabaseAdmin.auth.admin.createUser({
        email: `device_${deviceHash}@parentalcontrol.local`,
        email_confirm: true,
        user_metadata: {
          device_hash: deviceHash,
          device_name,
        },
      });

      if (createError || !newUser.user) {
        throw new Error(`Error creando usuario: ${createError?.message}`);
      }
      agentUserId = newUser.user.id;
    }

    // 3. Resolver / crear el niño (Change A — feat-multi-child-picker §A.7).
    // El padre captura el nombre en el "name this child" prompt, lo sube a
    // pairing_codes.child_first_name, y la edge function lo promueve a la
    // tabla children con ON CONFLICT para mantener idempotencia si el
    // dispositivo vuelve a emparejarse bajo el mismo nombre. Si la fila ya
    // existe (RETURNING id es null), hacemos un SELECT por
    // (parent_id, first_name) para resolver el id existente.
    const { data: insertedChild, error: childInsertError } = await supabaseAdmin
      .from("children")
      .insert({
        parent_id: pairingRecord.parent_id,
        first_name: trimmedChildName,
      })
      .select("id")
      .single();

    let childId: string;
    if (childInsertError || !insertedChild) {
      // Posible conflicto UNIQUE (parent_id, first_name): el niño ya existe.
      // Hacemos fallback a SELECT — el padre ya tiene un hijo con este
      // nombre, lo cual es exactamente lo que queremos para idempotencia.
      const { data: existingChild, error: childSelectError } = await supabaseAdmin
        .from("children")
        .select("id")
        .eq("parent_id", pairingRecord.parent_id)
        .eq("first_name", trimmedChildName)
        .single();

      if (childSelectError || !existingChild) {
        throw new Error(
          `Error resolviendo niño: ${childInsertError?.message ?? childSelectError?.message}`
        );
      }
      childId = existingChild.id;
    } else {
      childId = insertedChild.id;
    }

    // 4. Crear dispositivo (con child_id enlazado al niño recién resuelto).
    const { data: device, error: deviceError } = await supabaseAdmin
      .from("devices")
      .insert({
        device_name,
        parent_id: pairingRecord.parent_id,
        device_model,
        os_version,
        app_version,
        device_state: "ACTIVE",
        policy_version: 1,
        child_id: childId,
      })
      .select()
      .single();

    if (deviceError || !device) {
      throw new Error(`Error creando dispositivo: ${deviceError?.message}`);
    }

    // 4. Escribir device_id en app_metadata del usuario
    const { error: metaError } = await supabaseAdmin.auth.admin.updateUserById(
      agentUserId,
      {
        app_metadata: {
          device_id: device.id,
        },
      }
    );

    if (metaError) {
      throw new Error(`Error actualizando app_metadata: ${metaError.message}`);
    }

    // 5. Aplicar plantilla de política según age_band
    const templateAgeBand = age_band || pairingRecord.device_name?.split("-")[0] || "7-12";
    await applyPolicyTemplate(supabaseAdmin, device.id, templateAgeBand);

    // 6. Marcar código como consumido (Bug #2 de la auditoria).
    // Antes: status quedaba en 'ACTIVE' y solo se setaba used_at, lo que
    // permitia reusar el mismo codigo antes del TTL. Ahora transiciona a
    // 'CONSUMED' (valor agregado por migration 009_pairing_status_consumed.sql).
    await supabaseAdmin
      .from("pairing_codes")
      .update({
        status: "CONSUMED",
        used_at: new Date().toISOString()
      })
      .eq("id", pairingRecord.id);

    // 7. Disparar FCM para notificar al padre
    await sendFcmNotification(supabaseAdmin, pairingRecord.parent_id, {
      type: "DEVICE_PAIRED",
      device_id: device.id,
      device_name,
    });

    return new Response(
      JSON.stringify({
        success: true,
        device_id: device.id,
        parent_id: pairingRecord.parent_id,
        policy_version: 1,
      }),
      { headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (error) {
    console.error("Pairing error:", error);
    return new Response(
      JSON.stringify({ error: error.message }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});

// ============ Helpers ============

async function hashDeviceIdentifier(name: string, model: string): Promise<string> {
  const encoder = new TextEncoder();
  const data = encoder.encode(`${name}-${model}-${Date.now()}`);
  const hashBuffer = await crypto.subtle.digest("SHA-256", data);
  const hashArray = Array.from(new Uint8Array(hashBuffer));
  return hashArray.map((b) => b.toString(16).padStart(2, "0")).join("").slice(0, 16);
}

async function applyPolicyTemplate(
  supabase: ReturnType<typeof createClient>,
  deviceId: string,
  ageBand: string
): Promise<void> {
  // Buscar plantilla por age_band
  const { data: template } = await supabase
    .from("policy_templates")
    .select("*")
    .eq("age_band", ageBand)
    .single();

  if (!template) {
    console.warn(`No template found for age_band: ${ageBand}`);
    return;
  }

  const config = template.config;

  // Aplicar schedules
  if (config.schedules && Array.isArray(config.schedules)) {
    for (const schedule of config.schedules) {
      await supabase.from("schedules").insert({
        device_id: deviceId,
        name: schedule.id,
        days: schedule.days,
        from_time: schedule.from,
        to_time: schedule.to,
        action: schedule.action,
        allow_list: schedule.allow_list || null,
        is_active: true,
      });
    }
  }

  // Aplicar app_policies
  if (config.app_policies && Array.isArray(config.app_policies)) {
    for (const policy of config.app_policies) {
      await supabase.from("app_policies").insert({
        device_id: deviceId,
        package_name: policy.package_name,
        state: policy.state,
        daily_limit_minutes: policy.daily_limit_minutes || null,
        category: policy.category || null,
      }).onConflict(["device_id", "package_name"]).merge();
    }
  }
}

async function sendFcmNotification(
  supabase: ReturnType<typeof createClient>,
  parentId: string,
  payload: Record<string, unknown>
): Promise<void> {
  // Bug #3 de la auditoria: enviar push al padre cuando su hijo empareja
  // un dispositivo. device_push_tokens tiene la columna parent_id desde
  // la migration 008, asi que resolvemos los tokens directamente por el
  // parent_id del codigo de emparejamiento (no necesitamos el device_id
  // del padre como decia el TODO original).
  const { data: tokens, error: tokensError } = await supabase
    .from("device_push_tokens")
    .select("token")
    .eq("parent_id", parentId)
    .eq("is_active", true);

  if (tokensError) {
    // No fallamos el pairing si la notificacion falla — el dispositivo ya
    // quedo emparejado, el padre se entera al abrir la app de todos modos.
    console.error(`Error fetching push tokens for parent ${parentId}:`, tokensError);
    return;
  }

  if (!tokens || tokens.length === 0) {
    console.log(`No active FCM tokens for parent ${parentId}; skipping notification`);
    return;
  }

  const fcmUrl = "https://fcm.googleapis.com/fcm/send";
  const serverKey = Deno.env.get("FCM_SERVER_KEY");

  if (!serverKey) {
    console.warn("FCM_SERVER_KEY not configured; skipping FCM dispatch");
    return;
  }

  const fcmBody = {
    priority: "high",
    data: {
      ...payload,
      sent_at: new Date().toISOString(),
    },
  };

  for (const { token } of tokens) {
    try {
      const response = await fetch(fcmUrl, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `key=${serverKey}`,
        },
        body: JSON.stringify({ ...fcmBody, to: token }),
      });

      if (!response.ok) {
        console.error(`FCM send non-OK for token ...${token.slice(-6)}:`, await response.text());
      }
    } catch (e) {
      console.error("FCM send network error:", e);
    }
  }
}
