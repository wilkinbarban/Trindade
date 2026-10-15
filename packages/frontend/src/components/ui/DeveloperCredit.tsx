/**
 * The credit that travels with the application.
 *
 * Kept in one place so the signed-out screen and everything behind the session say the same thing, and
 * quiet enough that it never competes with the work it stands under.
 */
export function DeveloperCredit() {
  return (
    <div className="text-xs text-gray-400 leading-relaxed" data-testid="developer-credit">
      <div>Desenvolvido por Wilkin Barban Rosabal</div>
      <div>E-mail: wilkin.barban@gmail.com</div>
    </div>
  );
}
