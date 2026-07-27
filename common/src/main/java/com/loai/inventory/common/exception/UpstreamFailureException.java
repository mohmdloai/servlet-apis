package com.loai.inventory.common.exception;

/**
 * A dependency the request needed did not answer — HTTP <b>502</b>. The request itself was valid
 * and the local side-effects it asked for may well have happened; what failed is the hop outward.
 *
 * <p>Distinct from a 500 on purpose: a 500 says "we are broken", a 502 says "the part of this that
 * leaves the building did not". The difference is actionable — the caller retries a 502.
 *
 * <p>First use: {@code POST /api/admin/users/{id}/resend-verification}, where the verification
 * token is minted and committed before the mail is handed to the provider. Reporting a send failure
 * as success would tell an operator a rescue link is in someone's inbox when it is not, which is
 * the one outcome that endpoint exists to make impossible.
 */
public class UpstreamFailureException extends AppException {
  public UpstreamFailureException(String message) {
    super(502, message);
  }

  public UpstreamFailureException(String message, Throwable cause) {
    super(502, message, cause);
  }
}
