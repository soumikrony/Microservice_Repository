const TOKEN_KEY = "shopsphere.accessToken";
const EXPIRY_KEY = "shopsphere.tokenExpiry";

function clearSession() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(EXPIRY_KEY);
}

function parseClaims(jwt) {
  try {
    const encoded = jwt.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    return JSON.parse(atob(encoded + "=".repeat((4 - encoded.length % 4) % 4)));
  } catch {
    return {};
  }
}

document.getElementById("loginForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  const message = document.getElementById("loginMessage");
  message.textContent = "Signing in...";
  message.className = "form-message";
  try {
    const response = await fetch("/auth/token", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        username: document.getElementById("username").value.trim(),
        password: document.getElementById("password").value
      })
    });
    const data = await response.json();
    if (!response.ok || !data.access_token) {
      throw new Error(data.message || data.error || `Login failed (HTTP ${response.status})`);
    }
    const claims = parseClaims(data.access_token);
    const expiresAt = Number(claims.exp || Math.floor(Date.now() / 1000) + Number(data.expires_in || 3600));
    localStorage.setItem(TOKEN_KEY, data.access_token);
    localStorage.setItem(EXPIRY_KEY, String(expiresAt * 1000));
    window.location.replace("/index.html");
  } catch (error) {
    clearSession();
    message.textContent = error.message;
    message.className = "form-message error";
  }
});
