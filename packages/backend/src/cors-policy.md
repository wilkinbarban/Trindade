# Browser origin policy

CORS permits only `https://trindademassas.com.br`, regardless of `NODE_ENV`.
Other browser origins (including localhost and `null`) receive no CORS permission.
Native requests without `Origin` remain compatible; authentication is still required.
CORS is not a network firewall or authorization check: a denied origin may still
reach a route. HTTPS, reverse-proxy isolation and internal backend networking must
be enforced separately at deployment; this local change does not verify them.
