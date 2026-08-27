import { redirect } from 'next/navigation';

/** Contract 14: `/` redirects to the Merchant Control Tower. */
export default function RootPage() {
  redirect('/control-tower');
}
