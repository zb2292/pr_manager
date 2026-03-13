// 游戏数据
const gamesData = [
    {
        id: 1,
        title: "塞尔达传说：王国之泪",
        category: "action",
        rating: 9.8,
        popularity: 95,
        releaseDate: "2023-05-12",
        image: "https://media.rawg.io/media/games/699/6992da74314c407886a117076a445482.jpg",
        players: "20M+",
        description: "林克再次启程，探索天空、地面与地下的广阔海拉鲁。"
    },
    {
        id: 2,
        title: "博德之门3",
        category: "rpg",
        rating: 9.7,
        popularity: 88,
        releaseDate: "2023-08-03",
        image: "https://media.rawg.io/media/games/0c3/0c3167d614c0032071adc77a1f238d3c.jpg",
        players: "15M+",
        description: "召集你的队伍，回到被遗忘的国度，展开一段关于友谊与背叛、牺牲与生存，以及绝对权力的传奇故事。"
    },
    {
        id: 3,
        title: "艾尔登法环",
        category: "action",
        rating: 9.5,
        popularity: 92,
        releaseDate: "2022-02-25",
        image: "https://media.rawg.io/media/games/511/5118cc3c465521e1b07213f036c35182.jpg",
        players: "25M+",
        description: "在交界地展开宏大的奇幻冒险，揭开艾尔登法环力量的奥秘。"
    },
    {
        id: 4,
        title: "霍格沃茨之遗",
        category: "rpg",
        rating: 9.2,
        popularity: 85,
        releaseDate: "2023-02-10",
        image: "https://media.rawg.io/media/games/010/010419266710486c4349ad1508da22c0.jpg",
        players: "22M+",
        description: "体验1800年代的霍格沃茨。你将扮演一名掌握着可能撕裂魔法世界古老秘密钥匙的学生。"
    },
    {
        id: 5,
        title: "EA Sports FC 24",
        category: "sports",
        rating: 8.9,
        popularity: 90,
        releaseDate: "2023-09-29",
        image: "https://media.rawg.io/media/games/911/9118501239561917f309995c6f62486a.jpg",
        players: "35M+",
        description: "最真实的足球体验，引入了全新的 Rush 模式和更加深度的战术控制。"
    },
    {
        id: 6,
        title: "极限竞速：地平线 5",
        category: "racing",
        rating: 9.1,
        popularity: 83,
        releaseDate: "2021-11-09",
        image: "https://media.rawg.io/media/games/082/082365507c04224023f301ffdec97cb1.jpg",
        players: "37M+",
        description: "在墨西哥充满活力、不断变化的开放世界画面中，驾驶百辆世界级好车，享受无拘无束的驾驶乐趣。"
    },
    {
        id: 7,
        title: "文明 VI",
        category: "strategy",
        rating: 9.3,
        popularity: 78,
        releaseDate: "2016-10-21",
        image: "https://media.rawg.io/media/games/13a/13a52818713b28ee83a053e13d07adef.jpg",
        players: "12M+",
        description: "建立起一个经得起时间考验的帝国。"
    },
    {
        id: 8,
        title: "马里奥赛车 8 豪华版",
        category: "racing",
        rating: 9.4,
        popularity: 87,
        releaseDate: "2017-04-28",
        image: "https://media.rawg.io/media/games/39a/39a8c084f74d9e03943343168285c57b.jpg",
        players: "65M+",
        description: "随时随地进行终极版本的《马里奥赛车 8》比赛。"
    },
    {
        id: 9,
        title: "NBA 2K24",
        category: "sports",
        rating: 8.7,
        popularity: 82,
        releaseDate: "2023-09-08",
        image: "https://media.rawg.io/media/games/7c4/7c448f72700e090003058869894e6c3a.jpg",
        players: "10M+",
        description: "在 NBA 2K24 中掌控全场，体验更真实的篮球竞技。"
    },
    {
        id: 10,
        title: "星际争霸 II",
        category: "strategy",
        rating: 9.0,
        popularity: 75,
        releaseDate: "2010-07-27",
        image: "https://media.rawg.io/media/games/19a/19a5157ff9d20133e0a094828675b924.jpg",
        players: "8M+",
        description: "终极即时战略游戏，三足鼎立的星际史诗。"
    },
    {
        id: 11,
        title: "巫师 3：狂猎",
        category: "rpg",
        rating: 9.6,
        popularity: 86,
        releaseDate: "2015-05-19",
        image: "https://media.rawg.io/media/games/618/618ad20f310638466458305f9ef53da1.jpg",
        players: "50M+",
        description: "扮演职业怪物猎人杰洛特，在开放世界中寻找预言之子。"
    },
    {
        id: 12,
        title: "我的世界",
        category: "action",
        rating: 9.1,
        popularity: 94,
        releaseDate: "2011-11-18",
        image: "https://media.rawg.io/media/games/b4e/b4e4f73d5aa4db666bc85d6131a19902.jpg",
        players: "238M+",
        description: "探索无限世界，建造从最简单的家到最宏伟的城堡的一切。"
    }
];

// 全局变量
let currentGames = [...gamesData];
let currentCategory = 'all';
let currentSort = 'rating';
let currentView = 'grid';
let displayedGames = 6;

// DOM元素
const gamesContainer = document.getElementById('gamesContainer');
const searchInput = document.getElementById('searchInput');
const sortSelect = document.getElementById('sortSelect');
const loadMoreBtn = document.getElementById('loadMoreBtn');
const navLinks = document.querySelectorAll('.nav-link');
const viewBtns = document.querySelectorAll('.view-btn');

// 初始化
document.addEventListener('DOMContentLoaded', () => {
    renderGames();
    setupEventListeners();
});

// 设置事件监听器
function setupEventListeners() {
    // 搜索功能
    searchInput.addEventListener('input', debounce(handleSearch, 300));
    
    // 分类筛选
    navLinks.forEach(link => {
        link.addEventListener('click', (e) => {
            e.preventDefault();
            handleCategoryChange(link.dataset.category);
            updateActiveNavLink(link);
        });
    });
    
    // 排序功能
    sortSelect.addEventListener('change', (e) => {
        currentSort = e.target.value;
        sortGames();
        renderGames();
    });
    
    // 视图切换
    viewBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            const view = btn.dataset.view;
            changeView(view);
            updateActiveViewBtn(btn);
        });
    });
    
    // 加载更多
    loadMoreBtn.addEventListener('click', loadMoreGames);
}

// 渲染游戏
function renderGames() {
    const gamesToShow = currentGames.slice(0, displayedGames);
    
    if (gamesToShow.length === 0) {
        gamesContainer.innerHTML = `
            <div style="grid-column: 1/-1; text-align: center; padding: 3rem;">
                <h3>没有找到相关游戏</h3>
                <p>尝试调整搜索条件或筛选选项</p>
            </div>
        `;
        return;
    }
    
    gamesContainer.innerHTML = gamesToShow.map((game, index) => `
        <div class="game-card" style="--index: ${index}" data-id="${game.id}">
            <div class="game-image">
                <img src="${game.image}" alt="${game.title}" loading="lazy" onerror="this.onerror=null; this.src='https://picsum.photos/seed/${game.id}${game.title}/280/200.jpg'">
                <div class="game-rank">#${game.id}</div>
                <div class="game-rating">⭐ ${game.rating}</div>
            </div>
            <div class="game-info">
                <h3 class="game-title">${game.title}</h3>
                <span class="game-category">${getCategoryName(game.category)}</span>
                <p style="color: #666; margin: 0.5rem 0; font-size: 0.9rem;">${game.description}</p>
                <div class="game-meta">
                    <div class="game-stats">
                        <div class="game-stat">
                            <span>👥</span>
                            <span>${game.players}</span>
                        </div>
                        <div class="game-stat">
                            <span>📅</span>
                            <span>${formatDate(game.releaseDate)}</span>
                        </div>
                    </div>
                    <div style="color: #ff6b6b; font-weight: 600;">
                        ${game.popularity}% 热度
                    </div>
                </div>
            </div>
        </div>
    `).join('');
    
    // 显示/隐藏加载更多按钮
    if (displayedGames >= currentGames.length) {
        loadMoreBtn.style.display = 'none';
    } else {
        loadMoreBtn.style.display = 'inline-block';
    }
    
    // 添加点击事件
    document.querySelectorAll('.game-card').forEach(card => {
        card.addEventListener('click', () => {
            const gameId = parseInt(card.dataset.id);
            showGameDetails(gameId);
        });
    });
}

// 搜索处理
function handleSearch(e) {
    const searchTerm = e.target.value.toLowerCase();
    
    if (searchTerm === '') {
        currentGames = getCurrentCategoryGames();
    } else {
        currentGames = getCurrentCategoryGames().filter(game => 
            game.title.toLowerCase().includes(searchTerm) ||
            game.description.toLowerCase().includes(searchTerm)
        );
    }
    
    sortGames();
    displayedGames = 6;
    renderGames();
}

// 分类切换
function handleCategoryChange(category) {
    currentCategory = category;
    currentGames = getCurrentCategoryGames();
    sortGames();
    displayedGames = 6;
    renderGames();
}

function getCurrentCategoryGames() {
    if (currentCategory === 'all') {
        return [...gamesData];
    }
    return gamesData.filter(game => game.category === currentCategory);
}

// 排序游戏
function sortGames() {
    switch (currentSort) {
        case 'rating':
            currentGames.sort((a, b) => b.rating - a.rating);
            break;
        case 'popularity':
            currentGames.sort((a, b) => b.popularity - a.popularity);
            break;
        case 'release':
            currentGames.sort((a, b) => new Date(b.releaseDate) - new Date(a.releaseDate));
            break;
        case 'name':
            currentGames.sort((a, b) => a.title.localeCompare(b.title, 'zh-CN'));
            break;
    }
}

// 视图切换
function changeView(view) {
    currentView = view;
    if (view === 'list') {
        gamesContainer.classList.add('list-view');
    } else {
        gamesContainer.classList.remove('list-view');
    }
}

// 加载更多游戏
function loadMoreGames() {
    displayedGames += 6;
    renderGames();
}

// 显示游戏详情
function showGameDetails(gameId) {
    const game = gamesData.find(g => g.id === gameId);
    if (!game) return;
    
    // 创建模态框显示游戏详情
    const modal = document.createElement('div');
    modal.style.cssText = `
        position: fixed;
        top: 0;
        left: 0;
        width: 100%;
        height: 100%;
        background: rgba(0, 0, 0, 0.8);
        display: flex;
        align-items: center;
        justify-content: center;
        z-index: 2000;
        opacity: 0;
        animation: fadeIn 0.3s forwards;
    `;
    
    modal.innerHTML = `
        <div style="
            background: white;
            padding: 2rem;
            border-radius: 15px;
            max-width: 500px;
            width: 90%;
            position: relative;
            transform: scale(0.9);
            animation: scaleIn 0.3s forwards;
        ">
            <button onclick="this.parentElement.parentElement.remove()" style="
                position: absolute;
                top: 1rem;
                right: 1rem;
                background: none;
                border: none;
                font-size: 1.5rem;
                cursor: pointer;
                color: #999;
            ">×</button>
            
            <div style="text-align: center; margin-bottom: 1.5rem;">
                <img src="${game.image}" alt="${game.title}" style="width: 100%; max-width: 300px; height: 200px; object-fit: cover; border-radius: 10px; margin-bottom: 1rem;" onerror="this.onerror=null; this.src='https://picsum.photos/seed/${game.id}${game.title}/300/200.jpg'">
                <h2 style="margin: 0; color: #333;">${game.title}</h2>
                <span style="
                    background: rgba(102, 126, 234, 0.1);
                    color: #667eea;
                    padding: 0.25rem 0.75rem;
                    border-radius: 15px;
                    font-size: 0.8rem;
                    font-weight: 600;
                ">${getCategoryName(game.category)}</span>
            </div>
            
            <div style="margin-bottom: 1.5rem;">
                <p style="color: #666; line-height: 1.6;">${game.description}</p>
            </div>
            
            <div style="display: grid; grid-template-columns: repeat(2, 1fr); gap: 1rem; margin-bottom: 1.5rem;">
                <div style="text-align: center; padding: 1rem; background: #f8f9fa; border-radius: 10px;">
                    <div style="font-size: 1.5rem; font-weight: bold; color: #ff6b6b;">⭐ ${game.rating}</div>
                    <div style="color: #999; font-size: 0.9rem;">评分</div>
                </div>
                <div style="text-align: center; padding: 1rem; background: #f8f9fa; border-radius: 10px;">
                    <div style="font-size: 1.5rem; font-weight: bold; color: #667eea;">${game.popularity}%</div>
                    <div style="color: #999; font-size: 0.9rem;">热度</div>
                </div>
            </div>
            
            <div style="display: flex; justify-content: space-between; margin-bottom: 1.5rem;">
                <div>
                    <strong>玩家数量:</strong> ${game.players}
                </div>
                <div>
                    <strong>发布日期:</strong> ${formatDate(game.releaseDate)}
                </div>
            </div>
            
            <button onclick="this.parentElement.parentElement.remove()" style="
                width: 100%;
                background: linear-gradient(135deg, #667eea, #764ba2);
                color: white;
                border: none;
                padding: 1rem;
                border-radius: 10px;
                font-weight: 600;
                cursor: pointer;
            ">关闭</button>
        </div>
    `;
    
    document.body.appendChild(modal);
    
    // 点击背景关闭
    modal.addEventListener('click', (e) => {
        if (e.target === modal) {
            modal.remove();
        }
    });
}

// 工具函数
function getCategoryName(category) {
    const names = {
        action: '动作',
        rpg: '角色扮演',
        strategy: '策略',
        sports: '体育',
        racing: '竞速'
    };
    return names[category] || '其他';
}

function getGameIcon(category) {
    const icons = {
        action: '⚔️',
        rpg: '🐉',
        strategy: '🏛️',
        sports: '⚽',
        racing: '🏎️'
    };
    return icons[category] || '🎮';
}

function formatDate(dateString) {
    const date = new Date(dateString);
    return date.toLocaleDateString('zh-CN');
}

function debounce(func, wait) {
    let timeout;
    return function executedFunction(...args) {
        const later = () => {
            clearTimeout(timeout);
            func(...args);
        };
        clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };
}

function updateActiveNavLink(activeLink) {
    navLinks.forEach(link => link.classList.remove('active'));
    activeLink.classList.add('active');
}

function updateActiveViewBtn(activeBtn) {
    viewBtns.forEach(btn => btn.classList.remove('active'));
    activeBtn.classList.add('active');
}

// 添加CSS动画
const style = document.createElement('style');
style.textContent = `
    @keyframes fadeIn {
        to { opacity: 1; }
    }
    @keyframes scaleIn {
        to { transform: scale(1); }
    }
`;
document.head.appendChild(style);