import { redirect } from 'next/navigation';

const buildLoginTarget = (searchParams = {}) => {
  const query = new URLSearchParams();

  Object.entries(searchParams).forEach(([key, value]) => {
    if (Array.isArray(value)) {
      value.forEach((item) => query.append(key, item));
      return;
    }

    if (value != null) {
      query.set(key, value);
    }
  });

  const queryString = query.toString();
  return queryString ? `/?${queryString}` : '/';
};

const LoginRedirectPage = async ({ searchParams } = {}) => {
  redirect(buildLoginTarget(await searchParams));
};

export { buildLoginTarget };

export default LoginRedirectPage;
