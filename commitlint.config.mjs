export default {
    extends: ['@commitlint/config-conventional'],
    rules: {
        'subject-case': [0],
        'subject-full-stop': [0],
        'header-max-length': [2, 'always', 130],
        'body-max-line-length': [0],
    }
};