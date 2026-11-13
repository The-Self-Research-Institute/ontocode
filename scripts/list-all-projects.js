const { newPool } = require('./lib/db');

async function listProjects() {
    console.log('Listing all projects...\n');

    const pool = newPool();

    try {
        const projects = (await pool.query(
            'select p.project_id, p.name, p.status, p.owner_id, p.workspace_id, p.updated_at, u.email as owner_email ' +
            'from project p left join app_user u on u.id = p.owner_id ' +
            'where coalesce(p.is_deleted, false) = false order by p.updated_at desc nulls last'
        )).rows;

        console.log(`Total projects: ${projects.length}\n`);

        if (projects.length === 0) {
            console.log('No projects found in database!');
            return;
        }

        console.log('Projects:');
        console.log('-'.repeat(100));

        projects.forEach((project, index) => {
            const updated = project.updated_at ? new Date(project.updated_at).toLocaleString() : 'unknown';
            console.log(`${index + 1}. ID: ${project.project_id}`);
            console.log(`   Name: ${project.name || 'no name'}`);
            console.log(`   Owner: ${project.owner_email || project.owner_id || '(no owner)'}`);
            console.log(`   Status: ${project.status || 'unknown'}`);
            console.log(`   Updated: ${updated}`);
            console.log('');
        });

        console.log('-'.repeat(100));

        const ownerCounts = {};
        projects.forEach(p => {
            const owner = p.owner_email || p.owner_id || '(no owner)';
            ownerCounts[owner] = (ownerCounts[owner] || 0) + 1;
        });

        console.log('\nProjects by owner:');
        Object.entries(ownerCounts).forEach(([owner, count]) => {
            console.log(`   ${owner}: ${count} project(s)`);
        });
    } catch (error) {
        console.error('Error:', error.message);
        process.exitCode = 1;
    } finally {
        await pool.end();
    }
}

listProjects();
